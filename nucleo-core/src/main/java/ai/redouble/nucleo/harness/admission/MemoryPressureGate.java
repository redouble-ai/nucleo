/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.http.*;
import org.slf4j.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;
import java.util.function.*;

/**
 * Heap-aware back-pressure on new work. Running jobs continue unimpeded; only jobs that have
 * not started yet are held, and only through the account contract {@link Admission} consults
 * before any other account at every grant attempt. Only jobs that declared resources meet this
 * gate: an orchestrator holds nothing the gate manages and is never refused by it.
 *
 * <p><b>Critical latch.</b> {@link #fits} answers false while the latch is set: set when a read
 * sees the heap at or above {@link #CRITICAL_THRESHOLD}, cleared only when a read sees it below
 * {@link #RECOVERY_THRESHOLD}. No resourceful job is granted while it is set, and memory is never
 * reserved or bookable in advance. Every job the latch refuses is held on this account; those
 * jobs are memory's line.
 *
 * <p><b>The drain.</b> While memory's line is non-empty, two things can happen, and whichever
 * comes first wakes the evaluator: a job finishes, or the throttle delay for the heap ratio as it
 * reads now elapses since the last release. The evaluator re-reads the heap; if the latch is not
 * set it releases one job, the first in line that can go, and the next check is the delay for the
 * reading at that moment, {@link #MIN_THROTTLE_DELAY_MS} at the floor, up the cubic curve to
 * {@link #MAX_THROTTLE_DELAY_MS} at the critical boundary. A completion releases at most one, and
 * nothing accumulates: there are no memory seats to count. A job that arrives while the line
 * drains joins it. With nobody in the line, a job starts at once; a line held on some other
 * account is that account's to release whatever the heap reads.
 *
 * <p><b>Spacing curve</b> (cubic, 80% to 95%):
 * <pre>
 *   below 80% -> 50ms     80% -> 50ms     85% -> ~1.1s     90% -> ~8.9s     95% -> latched
 * </pre>
 *
 * <p><b>Growth penalty.</b> The curve alone is a function of the level, and the same level reached
 * fast is a different situation from the same level reached slowly. So the wait is the curve's
 * value multiplied by a penalty that the live set's net growth over the last two releases, in
 * percentage points, moves at every release: growth multiplies it by {@link #GROWTH_PENALTY_BASE}
 * to the points gained, a fall divides it by the base to the points lost, and no net growth keeps
 * it where it is. A live set that went 80, 83, 86 across releases waits sixty-four times the
 * curve; if it then goes 89, 92, 89, 92 the sixty-four is holding it and stays; if it climbs on to
 * 89 the sixty-four compounds to four thousand. A jump from 80 to 92 in a single release is four
 * thousand at once. The wait is capped at {@link #MAX_PENALIZED_DELAY_MS}, and the penalty itself
 * at {@link #MAX_GROWTH_PENALTY}, the cap over the shortest wait, so that a fall of seventeen
 * points from any state is a reset. Every look computes the penalty a release now would commit,
 * so a heap that empties clears it at the next look, and the two-second re-check keeps looking
 * under any wait.
 *
 * <p><b>The floor.</b> Used heap is the live set plus the garbage allocated since the last
 * collection, and between collections the garbage part swings by tens of points in seconds, so
 * the reading itself says nothing about growth. Used heap only falls when a collection ran, so a
 * reading lower than the previous look's is one taken after a collection: the live set plus what
 * was allocated since. That reading is the floor, it holds until the next fall, and the penalty
 * measures its growth. The curve and the latch read the level, which is the reading as it is.
 *
 * <p>A completion is a moment to look, not a token to spend: the heap reading decides at every
 * look, and a completion that lands under the latch earns nothing. Looking at completions is
 * what keeps the collector running. A drain paced by the delay alone can go minutes between
 * releases on a long line at a high reading, nothing allocates in that time, the young
 * generation never fills, the collector never runs, and the reading it is waiting on never
 * moves; a release on each completion the heap allows keeps work flowing, so the reading keeps
 * moving too.
 *
 * <p>Garbage collection emits no event, so while anything is pending {@link #earliestFit} names a
 * re-check at most {@link #CRITICAL_CHECK_INTERVAL_MS} ahead. That is the one deliberate timed
 * re-check in admission.
 *
 * <p><b>In the log.</b> The health snapshot prints this gate's {@link #getStatus() status} every
 * time, and the gate logs the latch setting at warn and clearing at info, each with the same
 * summary, so the state that refused or released a job is on record at the moment it did. The
 * status names a zone: {@code CRITICAL} while latched, {@code DRAINING} while memory's line is
 * non-empty, {@code GREEN} otherwise.
 *
 * <p><b>Shutdown.</b> Once {@link #stop()} has been called the gate answers true to everyone:
 * nothing is held back by the heap while the dispatcher drains, so no parked job is stranded.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-03)
 */
public class MemoryPressureGate extends AbstractRateLimiter<Void> implements Stoppable {
    private static final Logger log = LoggerFactory.getLogger(MemoryPressureGate.class);
    private static volatile MemoryPressureGate instance;

    /** Heap ratio where the curve begins; below it the drain runs at the floor. */
    static final double THROTTLE_START = 0.80;

    /** Heap ratio at which the latch sets and admission grants nothing resourceful. */
    static final double CRITICAL_THRESHOLD = 0.95;

    /** Heap ratio to which a latched gate must recover before it clears (hysteresis). */
    static final double RECOVERY_THRESHOLD = 0.90;

    /** Maximum spacing, at the critical boundary. */
    static final long MAX_THROTTLE_DELAY_MS = 30_000;

    /** Spacing at and below the start of the curve. */
    static final long MIN_THROTTLE_DELAY_MS = 50;

    /**
     * The growth penalty's base. At each release the penalty is multiplied by this raised to the
     * heap's net growth over the last two releases, in percentage points, so six points gained
     * multiply it by sixty-four, six points lost divide it by sixty-four, and no net growth keeps it.
     */
    static final double GROWTH_PENALTY_BASE = 2.0;

    /** The longest wait the penalty can impose; the two-second re-check keeps looking underneath it. */
    static final long MAX_PENALIZED_DELAY_MS = 3_600_000;

    /** The penalty's ceiling, the longest wait over the shortest, so that a fall of seventeen points from any state is a reset. */
    static final double MAX_GROWTH_PENALTY = (double) MAX_PENALIZED_DELAY_MS / MIN_THROTTLE_DELAY_MS;

    /**
     * Re-check interval while anything is pending. A job's memory retention is not reclaimed the
     * instant its resources close, the JVM does not reflect lower heap usage until the next
     * collection, and the collection signals nothing. The tick bridges that gap for the evaluator.
     */
    static final long CRITICAL_CHECK_INTERVAL_MS = 2_000;
    private static final long CRITICAL_CHECK_INTERVAL_NANOS =
            Duration.ofMillis(CRITICAL_CHECK_INTERVAL_MS).toNanos();

    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final AtomicBoolean latched = new AtomicBoolean(false);

    /** Jobs admission is holding on this account, installed by it. Zero until admission starts, which is correct: nothing is held yet. */
    private volatile IntSupplier heldHere = () -> 0;

    /** When the last job was released, or null before the first. Guarded by {@link #lock}. */
    private Long lastGrantNanos;

    /** A job finished since the last release, which lets the next one go without waiting out the delay. Guarded by {@link #lock}. */
    private boolean completedSinceRelease;

    /** The reading at the previous look, null before the first. Guarded by {@link #lock}. */
    private Double lastReading;

    /**
     * The floor: the most recent reading that was lower than the one before it. Used heap only
     * falls when a collection ran, so a lower reading is one taken after a collection, the live set
     * plus what was allocated since, and a higher reading is that plus garbage. The floor is the
     * live set as last seen, which is what the growth penalty prices; the level the curve and the
     * latch read is the reading itself. Valid after the first look. Guarded by {@link #lock}.
     */
    private double floor;

    /** The floor at the last release and at the one before it, null until they exist. Guarded by {@link #lock}. */
    private Double floorAtPreviousRelease;
    private Double floorBeforePreviousRelease;

    /** The growth penalty committed at the last release, 1 until the heap grows across two releases. Guarded by {@link #lock}. */
    private double growthPenalty = 1.0;

    /** Package-private so the tests can subclass with a fake heap and a fake clock. */
    MemoryPressureGate() {
    }

    public static MemoryPressureGate getInstance() {
        if (instance == null) {
            synchronized (MemoryPressureGate.class) {
                if (instance == null) {
                    instance = new MemoryPressureGate();
                }
            }
        }
        return instance;
    }

    @Override
    public void observeHeld(IntSupplier held) {
        this.heldHere = held;
    }

    /**
     * True unless the latch is set or, with memory's line pending, neither a completion nor the
     * delay since the last release has arrived.
     */
    @Override
    public boolean fits(List<Void> mine, List<Void> reservedAhead) {
        if (stopping.get()) {
            return true;
        }
        long now = nanoTime();
        lock.lock();
        try {
            double ratio = readHeap();
            return !isLatched(ratio) && releasable(now, ratio);
        }
        finally {
            lock.unlock();
        }
    }

    /**
     * The same answer as {@link #fits}, and on true stamps the release that paces the next one and
     * spends the completion, if one let it through. Amounts are ignored; memory is never reserved.
     */
    @Override
    public boolean tryTake(List<Void> mine, List<Void> reservedAhead) {
        if (stopping.get()) {
            return true;
        }
        long now = nanoTime();
        lock.lock();
        try {
            double ratio = readHeap();
            if (isLatched(ratio) || !releasable(now, ratio)) {
                return false;
            }
            lastGrantNanos = now;
            completedSinceRelease = false;
            growthPenalty = growthPenalty();
            floorBeforePreviousRelease = floorAtPreviousRelease;
            floorAtPreviousRelease = floor;
            return true;
        }
        finally {
            lock.unlock();
        }
    }

    /**
     * A resourceful job finished: the next one in memory's line may go without waiting out the
     * delay, and the evaluator is woken to release it. One completion, at most one release.
     */
    @Override
    public void give(List<Void> amounts) {
        lock.lock();
        try {
            completedSinceRelease = true;
        }
        finally {
            lock.unlock();
        }
        capacityChanged();
    }

    /**
     * While latched, the timed re-check. With memory's line pending, the instant the next release
     * is due or the re-check, whichever is sooner, so a heap that fell between two reads is seen
     * within {@link #CRITICAL_CHECK_INTERVAL_MS}. Null only when nothing paces: no release yet,
     * or nobody held here.
     *
     * <p>A due instant already in the past is returned as it is, never as null. The evaluator asks
     * this after walking the queue, and the delay can elapse during that walk; a past instant
     * tells it to look again at once, while null would tell it that only an event can change the
     * answer and park it with a full line that nothing left running will ever wake.
     */
    @Override
    public Long earliestFit(List<Void> mine, List<Void> reservedAhead) {
        long now = nanoTime();
        lock.lock();
        try {
            if (latched.get()) {
                return now + CRITICAL_CHECK_INTERVAL_NANOS;
            }
            Long ready = nextReleaseNanos(readHeap());
            if (ready == null) {
                return null;
            }
            return Math.min(ready, now + CRITICAL_CHECK_INTERVAL_NANOS);
        }
        finally {
            lock.unlock();
        }
    }

    /**
     * Whether the next job in memory's line may go: nobody is in the line, a job has finished since
     * the last release, or the delay since it has elapsed. Called under {@link #lock}.
     */
    private boolean releasable(long now, double ratio) {
        Long ready = nextReleaseNanos(ratio);
        return ready == null || completedSinceRelease || now - ready >= 0;
    }

    /**
     * The instant the delay since the last release elapses, or null when nothing paces: no release
     * yet, or admission is holding nobody on this account. Only memory's own line is memory's to
     * pace. Called under {@link #lock}.
     */
    private Long nextReleaseNanos(double ratio) {
        if (lastGrantNanos == null || heldHere.getAsInt() == 0) {
            return null;
        }
        return lastGrantNanos + penalizedDelay(ratio).toNanos();
    }

    /**
     * The wait before the next release: the curve's value at the given heap ratio multiplied by
     * the growth penalty a release now would commit, capped at {@link #MAX_PENALIZED_DELAY_MS}.
     * Called under {@link #lock}, after {@link #readHeap}.
     */
    Duration penalizedDelay(double ratio) {
        long curveMs = calculateThrottleDelay(ratio).toMillis();
        return Duration.ofMillis(Math.min(MAX_PENALIZED_DELAY_MS, Math.round(curveMs * growthPenalty())));
    }

    /**
     * The penalty a release now would commit: the one committed at the last release, multiplied
     * by {@link #GROWTH_PENALTY_BASE} raised to the floor's net growth in points since the release
     * before the last, which is a division when the floor fell, clamped between 1 and
     * {@link #MAX_GROWTH_PENALTY}. Two releases and their floors separate the shapes that matter:
     * a jump of twelve points in one release is a stop, a steady three points per release
     * compounds to one, a live set that holds where a climb stopped keeps whatever penalty got it
     * there, and a heap that empties is a reset. Garbage filling between collections raises the
     * reading and never the floor, so it prices nothing. Called under {@link #lock}, after
     * {@link #readHeap}.
     */
    double growthPenalty() {
        if (floorBeforePreviousRelease == null) {
            return growthPenalty;
        }
        double growthPoints = (floor - floorBeforePreviousRelease) * 100.0;
        double moved = growthPenalty * Math.pow(GROWTH_PENALTY_BASE, growthPoints);
        return Math.clamp(moved, 1.0, MAX_GROWTH_PENALTY);
    }

    /**
     * One look at the heap: the reading, and when it fell since the previous look, the new floor.
     * Every question the gate answers reads exactly once through here. Called under {@link #lock}.
     */
    private double readHeap() {
        double ratio = getHeapUsageRatio();
        if (lastReading == null || ratio < lastReading) {
            floor = ratio;
        }
        lastReading = ratio;
        return ratio;
    }

    /**
     * Moves the latch on the reading: set at or above {@link #CRITICAL_THRESHOLD}, cleared below
     * {@link #RECOVERY_THRESHOLD}. Called under {@link #lock}.
     */
    private boolean isLatched(double ratio) {
        if (latched.get()) {
            if (ratio < RECOVERY_THRESHOLD) {
                latched.set(false);
                log.info("Memory latch cleared: {}", snapshot(nanoTime()).summary());
                return false;
            }
            return true;
        }
        if (ratio >= CRITICAL_THRESHOLD) {
            latched.set(true);
            log.warn("Memory latch set: {}", snapshot(nanoTime()).summary());
            return true;
        }
        return false;
    }

    /**
     * The spacing between two releases at the given heap ratio: a cubic curve mapping
     * [{@link #THROTTLE_START}, {@link #CRITICAL_THRESHOLD}) to [{@link #MIN_THROTTLE_DELAY_MS},
     * {@link #MAX_THROTTLE_DELAY_MS}], and the floor below {@link #THROTTLE_START}.
     */
    static Duration calculateThrottleDelay(double ratio) {
        double normalized = Math.clamp((ratio - THROTTLE_START) / (CRITICAL_THRESHOLD - THROTTLE_START), 0.0, 1.0);
        long delayMs = (long) (MAX_THROTTLE_DELAY_MS * normalized * normalized * normalized);
        return Duration.ofMillis(Math.max(delayMs, MIN_THROTTLE_DELAY_MS));
    }

    @Override
    public String limiterName() {
        return "memory";
    }

    @Override
    public String limiterCategory() {
        return "memory";
    }

    @Override
    public long capacity() {
        return Runtime.getRuntime().maxMemory();
    }

    @Override
    public long currentInUse() {
        return usedBytes();
    }

    @Override
    public String statusIndicator() {
        if (latched.get()) {
            return "blocked";
        }
        long now = nanoTime();
        lock.lock();
        try {
            Long ready = nextReleaseNanos(readHeap());
            return ready != null && !completedSinceRelease && ready - now > 0 ? "pace:" + (ready - now) / 1_000_000L + "ms" : null;
        }
        finally {
            lock.unlock();
        }
    }

    @Override
    public void onSuccess() {
        // Memory pressure is observed from the JVM, not learned from upstream feedback.
    }

    @Override
    public void onRateLimitError(UpstreamFailure failure) {
        // No external rate-limit signal applies to a local heap gate.
    }

    long usedBytes() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    double getHeapUsageRatio() {
        long max = capacity();
        if (max <= 0) return 0.0;
        return (double) usedBytes() / max;
    }

    /** Package-private so the tests can drive the clock. */
    long nanoTime() {
        return System.nanoTime();
    }

    /**
     * Returns current gate status for monitoring.
     */
    @Override
    public MemoryPressureStatus getStatus() {
        long now = nanoTime();
        lock.lock();
        try {
            return snapshot(now);
        }
        finally {
            lock.unlock();
        }
    }

    /** The state as it stands. Called under {@link #lock}. */
    private MemoryPressureStatus snapshot(long now) {
        double ratio = readHeap();
        Long ready = nextReleaseNanos(ratio);
        long pacingMs = ready != null && !completedSinceRelease && ready - now > 0 ? (ready - now) / 1_000_000L : 0;
        int held = heldHere.getAsInt();
        String zone = latched.get() ? "CRITICAL" : held > 0 ? "DRAINING" : "GREEN";
        return new MemoryPressureStatus(
                usedBytes(),
                capacity(),
                ratio,
                floor,
                zone,
                held,
                pacingMs,
                growthPenalty()
        );
    }

    @Override
    public boolean isStopping() {
        return stopping.get();
    }

    @Override
    public void stop() {
        if (stopping.compareAndSet(false, true)) {
            log.info("Stopping MemoryPressureGate");
            capacityChanged();
        }
    }
}
