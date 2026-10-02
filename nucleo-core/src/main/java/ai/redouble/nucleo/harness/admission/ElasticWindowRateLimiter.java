/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.http.*;
import org.slf4j.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

/**
 * Sliding-window rate limiter with elastic throttling and a circuit breaker, as an admission
 * account: it never blocks, {@link Admission} does.
 *
 * <p>Subclasses define the base window duration and max requests per window.
 * Under pressure the effective window stretches:
 * {@code effectiveWindow = baseWindow * (1 + throttle)}.
 * At throttle 1.0 the window doubles (halving throughput).
 * At throttle 9.0 the window is 10x (1/10th throughput).
 *
 * <p>Pressure is signalled via {@link #onRateLimitError}. Recovery happens
 * gradually via {@link #onSuccess}. Template methods control aggressiveness
 * and recovery speed - override them for custom behavior.
 *
 * <p><strong>Circuit breaker:</strong> If enough failures happen while the limiter is already
 * at max throttle, the circuit transitions to {@code BLOCKED} and {@link #fits} refuses at once
 * with an LLM-readable message, telling the LLM the tool is temporarily unavailable so it can
 * try an alternative. After a cooldown the circuit may move to {@code PROBING}: one test
 * request is allowed. The probe slot belongs to the head of the admission queue: a waiter
 * asking with amounts reserved ahead of it is refused as if the probe were already in flight,
 * so a younger job never takes the one slot from the oldest waiter. Success closes the circuit
 * back to {@code HEALTHY}; failure re-opens it with exponentially longer cooldown (capped by
 * {@link #getMaxCooldownMs}). A probe that receives no verdict at all - the job that carried
 * it died before signalling - expires after {@link #getProbeTimeoutMs} and the circuit falls
 * back to {@code BLOCKED} with its cooldown preserved, so a lost probe can never leave the
 * circuit refusing everyone forever. A probe slower than that timeout allows a second probe in
 * flight; the first probe's late {@link #onSuccess} is harmless (it only closes a probing
 * circuit) and its late {@link #onRateLimitError} while blocked is a no-op.
 *
 * <p><strong>Concurrency:</strong> All lifecycle methods are {@code final}. The request window
 * is guarded by one lock; circuit state transitions go through a single {@link AtomicReference}
 * swap of an immutable {@code CircuitStatus} record, so readers always see a consistent
 * snapshot of state and timing, and {@link #tryTake} is atomic against transitions made by
 * client threads through {@link #onSuccess} and {@link #onRateLimitError}.
 *
 * <p><strong>Subclass gotcha:</strong> the parent constructor calls
 * {@link #getBaseWindowMs} and {@link #getMaxRequests} BEFORE subclass
 * instance fields are initialized. Subclasses MUST return values from
 * static sources (a constant, or a {@code Settings} read) rather than instance fields. If
 * instance-field computation is needed, factor it into a separate
 * initialization path not invoked during construction.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-11)
 */
public abstract class ElasticWindowRateLimiter extends AbstractRateLimiter<Void> {
    private static final Logger log = LoggerFactory.getLogger(ElasticWindowRateLimiter.class);
    /** Circuit state. HEALTHY = normal. BLOCKED = failing fast. PROBING = testing recovery. */
    public enum CircuitState { HEALTHY, BLOCKED, PROBING }

    /**
     * Immutable snapshot of circuit breaker state. Held as an AtomicReference
     * so transitions are a single atomic CAS - no field-by-field tearing,
     * no reader-writer races between state and its associated timing. Times are
     * {@link System#nanoTime()} values.
     */
    private record CircuitStatus(CircuitState state, long blockedUntilNanos, long currentCooldownMs, long probeSinceNanos) {
        static final CircuitStatus HEALTHY_STATUS = new CircuitStatus(CircuitState.HEALTHY, 0, 0, 0);
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final ArrayDeque<Long> requestNanos = new ArrayDeque<>();
    private final AtomicReference<Double> throttleCoefficient = new AtomicReference<>(0.0);
    private final AtomicInteger successCounter = new AtomicInteger(0);
    /** The instant of the last pressure signal; meaningless until one arrives, since the throttle is zero before it. */
    private final AtomicLong lastThrottleEventNanos = new AtomicLong(0);
    /**
     * What the upstream last said. Retained so the circuit-open error, which is raised long
     * after the failing call and on a different thread, can name the cause instead of leaving
     * the reader to correlate log lines by timestamp. Null until something actually fails.
     */
    private final AtomicReference<UpstreamFailure> lastFailure = new AtomicReference<>();
    // Circuit breaker state - a single atomic reference for consistent transitions.
    private final AtomicReference<CircuitStatus> circuit = new AtomicReference<>(CircuitStatus.HEALTHY_STATUS);
    private final AtomicInteger failuresAtMaxThrottle = new AtomicInteger(0);
    /**
     * Validates the template values once, each with {@link IllegalArgumentException} naming the
     * value: max requests, base window, initial cooldown, failures at max to block, successes
     * per decrease and probe timeout must be positive; max throttle must be non-negative; max
     * cooldown must be at least the initial cooldown. The template methods run here, before a
     * subclass's instance fields exist, which is the gotcha in the class javadoc.
     */
    protected ElasticWindowRateLimiter() {
        int max = getMaxRequests();
        if (max <= 0) {
            throw new IllegalArgumentException("maxRequests must be positive, got " + max);
        }
        if (getBaseWindowMs() <= 0) {
            throw new IllegalArgumentException("baseWindowMs must be positive, got " + getBaseWindowMs());
        }
        if (getMaxThrottle() < 0) {
            throw new IllegalArgumentException("maxThrottle must be non-negative, got " + getMaxThrottle());
        }
        if (getInitialCooldownMs() <= 0) {
            throw new IllegalArgumentException("initialCooldownMs must be positive, got " + getInitialCooldownMs());
        }
        if (getMaxCooldownMs() < getInitialCooldownMs()) {
            throw new IllegalArgumentException("maxCooldownMs must be >= initialCooldownMs, got max=" + getMaxCooldownMs() + ", initial=" + getInitialCooldownMs());
        }
        if (getFailuresAtMaxToBlock() <= 0) {
            throw new IllegalArgumentException("failuresAtMaxToBlock must be positive, got " + getFailuresAtMaxToBlock());
        }
        if (getSuccessesPerDecrease() <= 0) {
            throw new IllegalArgumentException("successesPerDecrease must be positive, got " + getSuccessesPerDecrease());
        }
        if (getProbeTimeoutMs() <= 0) {
            throw new IllegalArgumentException("probeTimeoutMs must be positive, got " + getProbeTimeoutMs());
        }
    }

    // ======================== Abstract: subclasses must define ========================

    /** Base window duration in milliseconds (e.g. 1000 for per-second, 60000 for per-minute). */
    protected abstract long getBaseWindowMs();

    /** Maximum requests allowed per window at zero throttle. */
    protected abstract int getMaxRequests();

    // ======================== Template methods: override for custom behavior ========================

    /** How much to increase throttle on each pressure signal. */
    protected double getThrottleIncrement() { return 1.0; }

    /** How much to decrease throttle per recovery step. */
    protected double getThrottleDecrement() { return 0.2; }

    /** Maximum throttle coefficient. */
    protected double getMaxThrottle() { return 9.0; }

    /** Number of consecutive successes needed before one decrement. */
    protected int getSuccessesPerDecrease() { return 3; }

    /** Minutes without pressure before switching to aggressive recovery. */
    protected long getTimeBasedRecoveryMinutes() { return 5; }

    /** Multiplier for decrement amount during time-based recovery. */
    protected double getTimeBasedRecoveryMultiplier() { return 2.5; }

    /** Consecutive failures while already at max throttle before opening the circuit. */
    protected int getFailuresAtMaxToBlock() { return 3; }

    /** Initial cooldown duration when the circuit first blocks. */
    protected long getInitialCooldownMs() { return 30_000; }

    /** Maximum cooldown duration. Exponential backoff caps here. */
    protected long getMaxCooldownMs() { return 3_600_000; }

    /**
     * How long a probe may stay in flight without a verdict before the circuit gives it up and
     * falls back to BLOCKED. Defaults to the maximum cooldown.
     */
    protected long getProbeTimeoutMs() { return getMaxCooldownMs(); }

    // ======================== Core logic (final - concurrency invariants) ========================

    /** Every elastic window fronts an HTTP service, so a demand naming one also names the shared HTTP connection gate. */
    @Override
    public final boolean requiresHttpConnection() {
        return true;
    }

    @Override
    public final boolean fits(List<Void> mine, List<Void> reservedAhead) {
        int needed = mine.size();
        refuseIfImpossible(needed);
        admissibleCircuit(!reservedAhead.isEmpty());
        lock.lock();
        try {
            long now = nanoTime();
            cleanup(now);
            return requestNanos.size() + needed + reservedAhead.size() <= getMaxRequests();
        }
        finally {
            lock.unlock();
        }
    }

    @Override
    public final boolean tryTake(List<Void> mine, List<Void> reservedAhead) {
        int needed = mine.size();
        refuseIfImpossible(needed);
        lock.lock();
        try {
            CircuitStatus current = admissibleCircuit(!reservedAhead.isEmpty());
            long now = nanoTime();
            cleanup(now);
            if (requestNanos.size() + needed + reservedAhead.size() > getMaxRequests()) {
                return false;
            }
            if (current.state() == CircuitState.BLOCKED) {
                // Cooldown expired and this caller is the head: claim the single probe.
                CircuitStatus probing = new CircuitStatus(CircuitState.PROBING, current.blockedUntilNanos(), current.currentCooldownMs(), now);
                if (!circuit.compareAndSet(current, probing)) {
                    return false;
                }
                log.info("{} circuit BLOCKED -> PROBING (cooldown expired, testing recovery)", getClass().getSimpleName());
            }
            for (int i = 0; i < needed; i++) {
                requestNanos.addLast(now);
            }
            return true;
        }
        finally {
            lock.unlock();
        }
    }

    /**
     * Rollback and compensation only. {@link Admission} never calls this on job completion -
     * the request was already issued upstream, so the window slot is spent regardless of how
     * quickly the job finished. The newest stamps are dropped, the ones a failed or rolled-back
     * grant just added, so every other waiter's deadline computed from the oldest stamps is
     * unchanged.
     */
    @Override
    public final void give(List<Void> amounts) {
        lock.lock();
        try {
            for (int i = 0; i < amounts.size() && !requestNanos.isEmpty(); i++) {
                requestNanos.pollLast();
            }
        }
        finally {
            lock.unlock();
        }
        // If nobody has signaled outcome yet and state is still PROBING, revert to
        // BLOCKED (preserving cooldown) so the circuit can try another probe later.
        revertProbeIfStillPending();
    }

    /**
     * The instant the k-th oldest stamp leaves the effective window, where k is the deficit
     * (stamps in the window plus the asked slots, less the maximum); now when nothing is short,
     * null when the asked slots can never fit the window.
     */
    @Override
    public final Long earliestFit(List<Void> mine, List<Void> reservedAhead) {
        int total = mine.size() + reservedAhead.size();
        if (total > getMaxRequests()) {
            return null;
        }
        lock.lock();
        try {
            long now = nanoTime();
            cleanup(now);
            int deficit = requestNanos.size() + total - getMaxRequests();
            if (deficit <= 0) {
                return now;
            }
            Iterator<Long> oldestFirst = requestNanos.iterator();
            long kth = 0;
            for (int i = 0; i < deficit; i++) {
                kth = oldestFirst.next();
            }
            return kth + TimeUnit.MILLISECONDS.toNanos(getEffectiveWindowMs());
        }
        finally {
            lock.unlock();
        }
    }

    @Override
    public final Replenishment replenishment() {
        return Replenishment.TIME;
    }

    private void refuseIfImpossible(int needed) {
        if (needed > getMaxRequests()) {
            throw new UncorrectableRuntimeLLMException(limiterName() + ": a demand of " + needed
                    + " requests can never fit a window of " + getMaxRequests());
        }
    }

    /**
     * Reverts a PROBING state back to BLOCKED (preserving cooldown), used when the
     * probe didn't produce any signal (rollback, compensation). Preserves
     * cooldown because we have no new evidence either way - the probe simply didn't
     * run the API call.
     *
     * <p>Safe to call from any thread. It does meaningful work only in the "no signal was
     * fired" path, which is exactly when the probe must self-heal back to BLOCKED. If an
     * outcome was already signaled, state is HEALTHY or BLOCKED by now and the CAS is a no-op.
     */
    private void revertProbeIfStillPending() {
        while (true) {
            CircuitStatus cur = circuit.get();
            if (cur.state() != CircuitState.PROBING) {
                return;
            }
            CircuitStatus next = new CircuitStatus(CircuitState.BLOCKED, cur.blockedUntilNanos(), cur.currentCooldownMs(), 0);
            if (circuit.compareAndSet(cur, next)) {
                log.info("{} circuit PROBING -> BLOCKED ({})", getClass().getSimpleName(), "probe did not produce a signal before its grant was taken back");
                return;
            }
        }
    }
    /**
     * A success resets the failure streak, closes a probing circuit, and every
     * {@link #getSuccessesPerDecrease} successes takes {@link #getThrottleDecrement} off the
     * throttle, never below zero; after {@link #getTimeBasedRecoveryMinutes} without pressure
     * every success takes the multiplied decrement. A relaxation wakes the evaluator.
     */
    @Override
    public final void onSuccess() {
        boolean grew = false;
        // A success resets the failure streak and closes the circuit if it was probing.
        failuresAtMaxThrottle.set(0);
        while (true) {
            CircuitStatus cur = circuit.get();
            if (cur.state() != CircuitState.PROBING) {
                break;
            }
            if (circuit.compareAndSet(cur, CircuitStatus.HEALTHY_STATUS)) {
                log.info("{} circuit PROBING -> HEALTHY (probe succeeded)", getClass().getSimpleName());
                grew = true;
                break;
            }
        }
        double currentThrottle = throttleCoefficient.get();
        if (currentThrottle > 0) {
            long minutesSinceLastEvent = TimeUnit.NANOSECONDS.toMinutes(nanoTime() - lastThrottleEventNanos.get());
            boolean timeBased = minutesSinceLastEvent >= getTimeBasedRecoveryMinutes();
            double decrement = timeBased ? getThrottleDecrement() * getTimeBasedRecoveryMultiplier() : getThrottleDecrement();
            int needed = timeBased ? 1 : getSuccessesPerDecrease();
            int count = successCounter.incrementAndGet();
            if (count >= needed) {
                if (successCounter.compareAndSet(count, 0)) {
                    double oldThrottle = throttleCoefficient.getAndUpdate(current ->
                            Math.max(current - decrement, 0.0));
                    double newThrottle = throttleCoefficient.get();
                    if (oldThrottle != newThrottle) {
                        grew = true;
                        log.info("{} throttle {} (effective window: {}s)",
                                getClass().getSimpleName(),
                                String.format("%.1f -> %.1f", oldThrottle, newThrottle),
                                getEffectiveWindowMs() / 1000);
                    }
                }
            }
        }
        if (grew) {
            capacityChanged();
        }
    }
    /**
     * Bumps throttle without affecting circuit state.
     *
     * <p>Use this for advisory pressure signals from upstream (e.g. HTTP response headers
     * like EPO's {@code X-Throttling-Control: red}) where the current request SUCCEEDED
     * but the upstream is telling us to slow down. Throttle climbs and the sliding
     * window stretches, but the circuit breaker is not triggered by advisories alone -
     * only real failures (exceptions) should open the circuit.
     */
    public final void onAdvisoryPressure() {
        double oldThrottle = throttleCoefficient.getAndUpdate(current ->
                Math.min(current + getThrottleIncrement(), getMaxThrottle()));
        lastThrottleEventNanos.set(nanoTime());
        double newThrottle = Math.min(oldThrottle + getThrottleIncrement(), getMaxThrottle());
        if (oldThrottle != newThrottle) {
            log.warn("{} advisory pressure, throttle {} (effective window: {}s)",
                    getClass().getSimpleName(),
                    String.format("%.1f -> %.1f", oldThrottle, newThrottle),
                    getEffectiveWindowMs() / 1000);
        }
    }

    @Override
    public final void onRateLimitError(UpstreamFailure failure) {
        lastFailure.set(failure);
        double oldThrottle = throttleCoefficient.getAndUpdate(current ->
                Math.min(current + getThrottleIncrement(), getMaxThrottle()));
        lastThrottleEventNanos.set(nanoTime());
        // Compute new throttle deterministically from this thread's update to avoid races
        // with concurrent onSuccess calls. This ensures atMax reflects what we just did.
        double newThrottle = Math.min(oldThrottle + getThrottleIncrement(), getMaxThrottle());
        log.warn("{} throttle {} (effective window: {}s) after {}",
                getClass().getSimpleName(),
                String.format("%.1f -> %.1f", oldThrottle, newThrottle),
                getEffectiveWindowMs() / 1000,
                failure.summary());
        boolean atMax = newThrottle >= getMaxThrottle();
        // Probe request failed - reopen with exponentially longer cooldown.
        // Transitions are atomic via CAS on the whole CircuitStatus record -
        // no stale reads of timing vs state, no write-order races.
        while (true) {
            CircuitStatus cur = circuit.get();
            if (cur.state() == CircuitState.PROBING) {
                // Clamp to [initial, max]. Constructor validates max >= initial, so this yields a valid range.
                long doubled = cur.currentCooldownMs() * 2;
                long next = Math.clamp(doubled, getInitialCooldownMs(), getMaxCooldownMs());
                CircuitStatus nextStatus = new CircuitStatus(CircuitState.BLOCKED, nanoTime() + TimeUnit.MILLISECONDS.toNanos(next), next, 0);
                if (circuit.compareAndSet(cur, nextStatus)) {
                    log.warn("{} circuit PROBING -> BLOCKED (probe failed, cooldown {}s) - {}", getClass().getSimpleName(), next / 1000, failure.summary());
                    return;
                }
                continue; // retry the loop; state changed under us
            }
            if (atMax && cur.state() == CircuitState.HEALTHY) {
                int failures = failuresAtMaxThrottle.incrementAndGet();
                if (failures < getFailuresAtMaxToBlock()) {
                    return;
                }
                long cd = getInitialCooldownMs();
                CircuitStatus nextStatus = new CircuitStatus(CircuitState.BLOCKED, nanoTime() + TimeUnit.MILLISECONDS.toNanos(cd), cd, 0);
                if (circuit.compareAndSet(cur, nextStatus)) {
                    log.warn("{} circuit HEALTHY -> BLOCKED ({} failures at max throttle, cooldown {}s) - {}",
                            getClass().getSimpleName(),
                            failures,
                            cd / 1000,
                            failure.summary());
                    return;
                }
                // CAS failed - state moved under us. Retry the loop; the increment is safe
                // to keep because onSuccess resets the counter and failure-in-BLOCKED is a no-op.
                continue;
            }
            return; // Nothing to do - state is BLOCKED or PROBING, or HEALTHY without atMax.
        }
    }
    @Override
    public final ElasticWindowStatus getStatus() {
        lock.lock();
        try {
            cleanup(nanoTime());
            return new ElasticWindowStatus(
                    getMaxRequests(),
                    getMaxRequests() - requestNanos.size(),
                    throttleCoefficient.get(),
                    getEffectiveWindowMs(),
                    circuit.get().state().name()
            );
        }
        finally {
            lock.unlock();
        }
    }

    /**
     * Default limiter name strips a trailing {@code RateLimiter} suffix from the
     * simple class name. Subclasses with structured names (e.g. per-route sub-buckets)
     * should override.
     */
    @Override
    public String limiterName() {
        String simple = getClass().getSimpleName();
        if (simple.endsWith("RateLimiter")) {
            return simple.substring(0, simple.length() - "RateLimiter".length());
        }
        return simple;
    }

    @Override
    public final String limiterCategory() {
        return "elastic_window";
    }

    @Override
    public final long capacity() {
        return getMaxRequests();
    }

    @Override
    public final long currentInUse() {
        lock.lock();
        try {
            cleanup(nanoTime());
            return requestNanos.size();
        }
        finally {
            lock.unlock();
        }
    }

    @Override
    public final String statusIndicator() {
        CircuitState state = circuit.get().state();
        if (state == CircuitState.BLOCKED) {
            return "blocked";
        }
        if (state == CircuitState.PROBING) {
            return "probing";
        }
        double throttle = throttleCoefficient.get();
        if (throttle > 0.0) {
            return String.format("throttle:%.1fx", 1.0 + throttle);
        }
        return null;
    }

    /** Current throttle coefficient. 0.0 = no throttle. */
    public final double getThrottleCoefficient() {
        return throttleCoefficient.get();
    }
    /** Effective window in ms, stretched by throttle. */
    public final long getEffectiveWindowMs() {
        return (long) (getBaseWindowMs() * (1.0 + throttleCoefficient.get()));
    }
    /** Current circuit state (for diagnostics). */
    public final CircuitState getCircuitState() {
        return circuit.get().state();
    }
    /** Milliseconds of cooldown left while blocked; zero otherwise (for diagnostics and tests). */
    public final long getCooldownRemainingMs() {
        CircuitStatus cur = circuit.get();
        if (cur.state() != CircuitState.BLOCKED) {
            return 0;
        }
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(cur.blockedUntilNanos() - nanoTime()));
    }
    /** Human-readable rendering of the nominal rate limit, e.g. {@code "10 requests / 60s"}. */
    private String formatRateLimit() {
        long windowMs = getBaseWindowMs();
        String windowStr = windowMs >= 60_000 && windowMs % 60_000 == 0
                ? (windowMs / 60_000) + "min"
                : (windowMs >= 1000 && windowMs % 1000 == 0 ? (windowMs / 1000) + "s" : windowMs + "ms");
        return getMaxRequests() + " requests / " + windowStr;
    }
    /**
     * Resolves the circuit for an admission question and either returns the state the caller
     * may proceed under or fails fast with an LLM-actionable message.
     *
     * <ul>
     *   <li>{@code HEALTHY} - proceed.</li>
     *   <li>{@code BLOCKED} with cooldown active - fail fast with remaining time.</li>
     *   <li>{@code BLOCKED} with cooldown expired - the head of the admission queue may proceed
     *       and claim the probe in {@link #tryTake}; a caller with amounts reserved ahead of it
     *       is refused as if the probe were already in flight, because the slot is the head's.</li>
     *   <li>{@code PROBING} - the probe is in flight; everyone is refused. A probe older than
     *       {@link #getProbeTimeoutMs} has expired: the circuit falls back to BLOCKED with its
     *       cooldown preserved and the question is asked again.</li>
     * </ul>
     *
     * @param followingHead true when the caller has amounts reserved ahead of it
     * @throws UncorrectableRuntimeLLMException if the circuit refuses the caller
     */
    private CircuitStatus admissibleCircuit(boolean followingHead) {
        while (true) {
            CircuitStatus cur = circuit.get();
            long now = nanoTime();
            if (cur.state() == CircuitState.HEALTHY) {
                return cur;
            }
            if (cur.state() == CircuitState.PROBING) {
                if (now - cur.probeSinceNanos() >= TimeUnit.MILLISECONDS.toNanos(getProbeTimeoutMs())) {
                    CircuitStatus expired = new CircuitStatus(CircuitState.BLOCKED, cur.blockedUntilNanos(), cur.currentCooldownMs(), 0);
                    if (circuit.compareAndSet(cur, expired)) {
                        log.warn("{} circuit PROBING -> BLOCKED (probe expired after {}s without a verdict)", getClass().getSimpleName(), getProbeTimeoutMs() / 1000);
                    }
                    continue;
                }
                throw refusal(cur, true);
            }
            if (now < cur.blockedUntilNanos()) {
                throw refusal(cur, false);
            }
            if (followingHead) {
                throw refusal(cur, true);
            }
            return cur;
        }
    }

    /**
     * The fail-fast message. The LLM already knows which tool it invoked; what it needs is WHY
     * we're refusing to run it. Names the limiter and what the upstream actually returned,
     * otherwise the message is unattributable.
     */
    private UncorrectableRuntimeLLMException refusal(CircuitStatus cur, boolean probing) {
        String rateLimit = formatRateLimit();
        UpstreamFailure cause = lastFailure.get();
        String because = cause != null ? " Last upstream response: " + cause.summary() + "." : "";
        String message;
        if (probing) {
            message = limiterName() + ": this tool is temporarily disabled because the upstream API "
                    + "rejected repeated requests. We are currently testing recovery with a single probe "
                    + "request. Do NOT retry this tool family right now - pick a different approach. "
                    + "(Rate limit: " + rateLimit + ".)" + because;
        }
        else {
            long remaining = Math.max(0, TimeUnit.NANOSECONDS.toSeconds(cur.blockedUntilNanos() - nanoTime()));
            message = limiterName() + ": this tool is temporarily disabled because the upstream API "
                    + "rejected repeated requests (exceeded the " + rateLimit + " budget, or triggered "
                    + "server-side anti-abuse). Cooldown is active - do NOT retry this tool family for ~"
                    + remaining + "s. Use a different tool or approach." + because;
        }
        return new UncorrectableRuntimeLLMException(message);
    }
    /** Drops stamps older than the effective window. Caller holds the lock. */
    private void cleanup(long now) {
        long cutoff = now - TimeUnit.MILLISECONDS.toNanos(getEffectiveWindowMs());
        while (!requestNanos.isEmpty() && requestNanos.peekFirst() < cutoff) {
            requestNanos.pollFirst();
        }
    }
    /** Length of the effective window as a {@link Duration}, for diagnostics. */
    public final Duration getEffectiveWindow() {
        return Duration.ofMillis(getEffectiveWindowMs());
    }

    /** The clock every window stamp, cooldown and recovery interval reads. Package-private so the tests can drive it. */
    long nanoTime() {
        return System.nanoTime();
    }
}
