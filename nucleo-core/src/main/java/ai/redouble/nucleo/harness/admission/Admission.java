/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import org.slf4j.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;
import java.util.function.*;

/**
 * The one place a job waits for resources. A job hands in its whole {@link Demand}; the
 * monitor grants it whole, in one critical section, or parks the job holding nothing. There is
 * no order in which resources are taken one after another, so nothing is ever held while
 * waiting for something else, and a metered debit lands at the instant of the grant, which is
 * the instant before the send.
 *
 * <p><b>Order.</b> Waiters form one FIFO queue in arrival order. The head is protected by
 * reservation: every waiter behind it asks each account with the head's amounts on that account
 * set aside, so it can only take surplus above what the head is waiting to receive. The head is
 * never overtaken and therefore never starves; jobs whose demands do not touch the head's
 * short accounts are granted at the first pass after they arrive.
 *
 * <p><b>Memory first, for resourceful jobs.</b> For a waiter whose demand has entries, the memory
 * gate is consulted before any of them, at every grant attempt, inline and on every pass, and the
 * grant takes from it like any other account so it can stamp the release and count the job in
 * flight. An orchestrator's empty demand never meets it. While the heap is critical no resourceful
 * job is granted. Whenever this monitor is holding jobs on the memory account, which is memory's
 * own line, the gate releases one job when a job finishes or when the throttle delay for the heap
 * as it reads at that attempt has elapsed, whichever comes first, so a line that built up under
 * the latch drains one job at a time when the latch clears rather than in the pass that clears
 * it. A lone arrival is never spaced, and a line held
 * on some other account is that account's to release. The gate learns the size of its line
 * through {@code observeHeld}, a lock-free mirror of this monitor's held count for it. Memory is
 * never reserved and never bookable in advance.
 *
 * <p><b>Waking.</b> One virtual thread, the evaluator, is the only thread that walks the queue.
 * Every state change unparks it: a grant released, a rollback, an account reporting that its
 * capacity grew, a new arrival. Metered shortfalls wake it at a computed deadline, the earliest
 * instant at which any waiter's guard could become true from the clock alone. Nothing polls. A
 * pass is one walk over the queue under the lock, asking each waiter's accounts once, so its
 * cost is linear in the waiters: a pass over ten thousand waiters completes in well under two
 * seconds.
 *
 * <p><b>Faults.</b> An account that throws from {@code fits}, {@code tryTake} or
 * {@code earliestFit} refuses only the waiter being evaluated: what the attempt already took is
 * given back inside the same critical section, the waiter receives the exception with the
 * account's message, and the pass continues. Anything else that throws inside a pass, an
 * account's {@code give} during compensation or this class itself, is a defect: admission ends,
 * every parked waiter fails with it as the cause, every later {@link #admit} refuses with it,
 * and it is logged once with its stack trace. A {@code give} that throws during settlement
 * propagates to the caller of {@link #release} or {@link #rollback}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
public final class Admission {
    private static final Logger log = LoggerFactory.getLogger(Admission.class);

    /** How transitions are reported. Production publishes through {@link LimiterEvents}; tests capture. */
    interface Emitter {
        void emit(LimiterIdentity account, JobSnapshot snapshot, int waiters, LimiterEvent.Type type, long waitNanos, String rejectReason, long amount);
    }

    private enum Outcome { GRANTED, HELD, REFUSED }

    private static final List<Void> NONE = Collections.emptyList();

    private final RateLimiter<Void> memory;
    private final LimiterIdentity memoryKey;
    private final LongSupplier nanos;
    private final Emitter emitter;
    private final ReentrantLock lock = new ReentrantLock();
    private final List<Waiter> queue = new ArrayList<>();
    private final Map<LimiterIdentity, Integer> heldCounts = new HashMap<>();
    private final Set<RateLimiter<?>> wired = new HashSet<>();
    private final Runnable wake = this::wakeEvaluator;
    /**
     * Mirror of {@link #heldCounts} for the memory account, published to the gate so it can tell
     * its own line from a queue held elsewhere. Read without taking this monitor's lock, which the
     * reader may already be inside.
     */
    private volatile int memoryHeld;
    private volatile Thread evaluator;
    private volatile boolean stopped;
    /** The throwable that ended the evaluator, when admission stopped on a defect rather than at shutdown. */
    private volatile Throwable defect;
    private Long nextDeadline;

    public Admission(RateLimiter<Void> memory) {
        this(memory, System::nanoTime, LimiterEvents::emit);
    }

    Admission(RateLimiter<Void> memory, LongSupplier nanos, Emitter emitter) {
        this.memory = memory;
        this.memoryKey = memory.accountFor(null);
        this.nanos = nanos;
        this.emitter = emitter;
    }

    /** Starts the evaluator. Called once by the dispatcher in {@code start()}; a second call throws {@link IllegalStateException}. */
    public void start() {
        if (evaluator != null) {
            throw new IllegalStateException("Admission already started");
        }
        lock.lock();
        try {
            wire(memory);
            memory.observeHeld(() -> memoryHeld);
        }
        finally {
            lock.unlock();
        }
        evaluator = Thread.ofVirtual().name("admission-evaluator").start(this::run);
    }

    /**
     * Stops admission: every parked waiter is refused with the same cancellation a drained
     * queued job receives at shutdown and reported REJECTED with reason {@code shutdown} on
     * every account it was held on, every wake slot the monitor held is cleared, and later
     * {@link #admit} calls refuse at once. Called by the dispatcher before it waits for
     * running jobs, so parked jobs end immediately instead of at the end of the shutdown timeout.
     */
    public void stop() {
        stopped = true;
        drainQueue("shutdown");
        wakeEvaluator();
    }

    /**
     * A pass threw something that is not an account's answer about a waiter: a defect in an
     * account's {@code give} or in this class. Admission stays closed, every parked waiter and
     * every later {@link #admit} fails with the defect as cause, and it is logged once with its
     * stack trace because an engineer has to fix it.
     */
    private void failAll(Throwable t) {
        log.error("Admission evaluator failed on a defect; every parked job fails and admission stays closed: {}", t.getMessage());
        log.error(t.getMessage(), t);
        defect = t;
        stopped = true;
        drainQueue("aborted");
    }

    /** Clears every wake slot and refuses every parked waiter with {@link #stoppedRefusal()}. The caller has set {@link #stopped}. */
    private void drainQueue(String reason) {
        lock.lock();
        try {
            long now = nanos.getAsLong();
            for (RateLimiter<?> limiter : wired) {
                limiter.onCapacityChange(null);
            }
            wired.clear();
            for (Waiter waiter : queue) {
                rejectHeld(waiter, reason, now);
                waiter.fail(stoppedRefusal());
            }
            queue.clear();
            nextDeadline = null;
        }
        finally {
            lock.unlock();
        }
    }

    /** What a waiter receives from stopped admission: the shutdown cancellation, or the defect that ended the evaluator. */
    private RuntimeException stoppedRefusal() {
        Throwable cause = defect;
        if (cause != null) {
            return new UncorrectableRuntimeLLMException("Admission failed on an internal defect: " + cause.getMessage(), cause);
        }
        return new CancellationException("Admission stopped: the dispatcher is shutting down");
    }

    /**
     * Grants the demand whole or parks the calling thread until it can. The job holds nothing
     * while parked. Returns the grant to settle later through {@link #release} or
     * {@link #rollback}.
     *
     * @throws InterruptedException  if the thread is interrupted while parked; the waiter is
     *                               withdrawn and anything granted concurrently is rolled back
     * @throws CancellationException if the job is cancelled while parked, or admission has stopped
     *                               at shutdown
     * @throws UncorrectableRuntimeLLMException if an account refuses the demand outright, or
     *                               admission has stopped on a defect
     */
    public Grant admit(Demand demand, JobContext<?> context) throws InterruptedException {
        long arrival = nanos.getAsLong();
        Waiter waiter = new Waiter(demand, context, Thread.currentThread(), arrival);
        lock.lock();
        try {
            if (stopped) {
                throw stoppedRefusal();
            }
            for (Demand.Entry<?> entry : demand.getEntries()) {
                wire(entry.getLimiter());
            }
            if (queue.isEmpty() && (!resourceful(demand) || memoryClear(waiter, arrival))) {
                Outcome outcome = attempt(waiter, null, arrival, false);
                if (outcome == Outcome.GRANTED) {
                    return waiter.grant;
                }
                if (outcome == Outcome.REFUSED) {
                    throw waiter.failure;
                }
            }
            queue.add(waiter);
            context.onCancel(waiter::wakeSelf);
            wakeEvaluator();
        }
        finally {
            lock.unlock();
        }
        return park(waiter);
    }

    /** Returns the held accounts of a finished job: every {@link RateLimiter.Replenishment#RELEASE} entry. */
    public void release(Grant grant) {
        settle(grant, false);
    }

    /** Returns everything a grant took because the work never happened: every entry, metered ones refunded. */
    public void rollback(Grant grant) {
        settle(grant, true);
    }

    /** Jobs parked in admission right now. */
    public int queueSize() {
        lock.lock();
        try {
            return queue.size();
        }
        finally {
            lock.unlock();
        }
    }

    private void settle(Grant grant, boolean everything) {
        if (!grant.settle()) {
            return;
        }
        lock.lock();
        try {
            for (Demand.Entry<?> entry : grant.getDemand().getEntries()) {
                if (everything || entry.getLimiter().replenishment() == RateLimiter.Replenishment.RELEASE) {
                    giveBack(entry, grant.getSnapshot());
                }
            }
            if (resourceful(grant.getDemand())) {
                memory.give(NONE);
            }
        }
        finally {
            lock.unlock();
        }
        wakeEvaluator();
    }

    private void giveBack(Demand.Entry<?> entry, JobSnapshot snapshot) {
        give(entry);
        emitter.emit(entry.getKey(), snapshot, heldCount(entry.getKey()), LimiterEvent.Type.RELEASED, 0, null, LimiterEvents.amountOf(entry.getAmounts()));
    }

    private static <T> void give(Demand.Entry<T> entry) {
        entry.getLimiter().give(entry.getAmounts());
    }

    private void wire(RateLimiter<?> limiter) {
        if (wired.add(limiter)) {
            limiter.onCapacityChange(wake);
        }
    }

    private void wakeEvaluator() {
        Thread thread = evaluator;
        if (thread != null) {
            LockSupport.unpark(thread);
        }
    }

    private Grant park(Waiter waiter) throws InterruptedException {
        while (true) {
            LockSupport.park(this);
            if (waiter.grant != null) {
                return waiter.grant;
            }
            if (waiter.failure != null) {
                throw waiter.failure;
            }
            if (Thread.interrupted()) {
                withdraw(waiter);
                throw new InterruptedException("Interrupted while waiting for admission");
            }
            if (waiter.context.isCancelled()) {
                withdraw(waiter);
                throw new CancellationException("Job cancelled while waiting for admission");
            }
        }
    }

    /** Leaves the queue from the waiter's own thread; a grant that landed concurrently is rolled back. */
    private void withdraw(Waiter waiter) {
        lock.lock();
        try {
            if (waiter.grant != null) {
                lock.unlock();
                try {
                    rollback(waiter.grant);
                }
                finally {
                    lock.lock();
                }
                return;
            }
            if (queue.remove(waiter)) {
                rejectHeld(waiter, "cancelled", nanos.getAsLong());
            }
        }
        finally {
            lock.unlock();
        }
    }

    private void run() {
        try {
            while (!stopped) {
                Long deadline;
                lock.lock();
                try {
                    evaluate();
                    deadline = nextDeadline;
                }
                finally {
                    lock.unlock();
                }
                if (stopped) {
                    return;
                }
                if (deadline == null) {
                    LockSupport.park(this);
                }
                else {
                    long delta = deadline - nanos.getAsLong();
                    if (delta > 0) {
                        LockSupport.parkNanos(this, delta);
                    }
                }
            }
        }
        catch (RuntimeException | Error t) {
            failAll(t);
        }
    }

    private void evaluate() {
        nextDeadline = null;
        if (queue.isEmpty()) {
            return;
        }
        long now = nanos.getAsLong();
        boolean progress = true;
        while (progress && !queue.isEmpty()) {
            progress = false;
            Waiter head = queue.getFirst();
            for (int i = 0; i < queue.size(); i++) {
                Waiter waiter = queue.get(i);
                if (waiter.context.isCancelled()) {
                    queue.remove(i);
                    rejectHeld(waiter, "cancelled", now);
                    waiter.fail(new CancellationException("Job cancelled while waiting for admission"));
                    progress = true;
                    break;
                }
                Outcome outcome = attempt(waiter, i == 0 ? null : head, now, i > 0 && head.memoryShort);
                if (outcome != Outcome.HELD) {
                    queue.remove(i);
                    progress = true;
                    break;
                }
            }
        }
        Long earliest = null;
        for (Waiter waiter : queue) {
            if (waiter.deadline != null && (earliest == null || waiter.deadline < earliest)) {
                earliest = waiter.deadline;
            }
        }
        nextDeadline = earliest;
    }

    /**
     * Whether the memory gate has any say over this demand. A demand with no entries belongs to
     * an orchestrator, which holds nothing the gate manages and can only fan out work it already
     * has, every fetch being a tool that meets the gate itself. Holding a coordinator would stall
     * the coordination that should be ready the moment the drain lets a tool through, for no
     * memory it would have protected; so an orchestrator never meets the gate, not the latch, not
     * the drain, not the check at arrival, and neither stamps a release nor counts in flight.
     */
    private static boolean resourceful(Demand demand) {
        return !demand.getEntries().isEmpty();
    }

    /** The memory check at the inline fast path: a refusal marks the waiter held on memory. */
    private boolean memoryClear(Waiter waiter, long now) {
        if (memory.fits(NONE, NONE)) {
            return true;
        }
        markHeld(waiter, memoryKey, now, 1);
        return false;
    }

    /**
     * One grant attempt for one waiter. {@code head} is the waiter whose amounts are reserved
     * ahead of this one, or null when this waiter is the head. On {@link Outcome#GRANTED} the
     * waiter carries its grant and has been unparked; on {@link Outcome#REFUSED} it carries its
     * failure and has been unparked; on {@link Outcome#HELD} it carries its deadline.
     */
    private Outcome attempt(Waiter waiter, Waiter head, long now, boolean behindMemoryShortHead) {
        List<Demand.Entry<?>> entries = waiter.demand.getEntries();
        boolean resourceful = resourceful(waiter.demand);
        if (resourceful && behindMemoryShortHead) {
            waiter.memoryShort = true;
            markHeld(waiter, memoryKey, now, 1);
            waiter.deadline = head.deadline;
            return Outcome.HELD;
        }
        if (resourceful && !memory.fits(NONE, NONE)) {
            waiter.memoryShort = true;
            markHeld(waiter, memoryKey, now, 1);
            waiter.deadline = memory.earliestFit(NONE, NONE);
            return Outcome.HELD;
        }
        waiter.memoryShort = false;
        boolean allFit = true;
        Long deadline = null;
        boolean eventOnly = false;
        for (Demand.Entry<?> entry : entries) {
            boolean fits;
            try {
                fits = fits(entry, head);
            }
            catch (Throwable t) {
                refuse(waiter, entry.getKey(), t, now);
                return Outcome.REFUSED;
            }
            if (!fits) {
                allFit = false;
                markHeld(waiter, entry.getKey(), now, LimiterEvents.amountOf(entry.getAmounts()));
                Long at;
                try {
                    at = earliestFit(entry, head);
                }
                catch (Throwable t) {
                    refuse(waiter, entry.getKey(), t, now);
                    return Outcome.REFUSED;
                }
                if (at == null) {
                    eventOnly = true;
                }
                else if (deadline == null || at > deadline) {
                    deadline = at;
                }
            }
        }
        if (!allFit) {
            waiter.deadline = eventOnly ? null : deadline;
            return Outcome.HELD;
        }
        List<Demand.Entry<?>> taken = new ArrayList<>();
        for (Demand.Entry<?> entry : entries) {
            boolean took;
            try {
                took = tryTake(entry, head);
            }
            catch (Throwable t) {
                giveBackAll(taken);
                refuse(waiter, entry.getKey(), t, now);
                return Outcome.REFUSED;
            }
            if (!took) {
                giveBackAll(taken);
                markHeld(waiter, entry.getKey(), now, LimiterEvents.amountOf(entry.getAmounts()));
                Long at;
                try {
                    at = earliestFit(entry, head);
                }
                catch (Throwable t) {
                    refuse(waiter, entry.getKey(), t, now);
                    return Outcome.REFUSED;
                }
                waiter.deadline = at;
                return Outcome.HELD;
            }
            taken.add(entry);
        }
        if (resourceful && !memory.tryTake(NONE, NONE)) {
            waiter.memoryShort = true;
            giveBackAll(taken);
            markHeld(waiter, memoryKey, now, 1);
            waiter.deadline = memory.earliestFit(NONE, NONE);
            return Outcome.HELD;
        }
        Map<LimiterIdentity, Long> heldNanos = new LinkedHashMap<>();
        for (Map.Entry<LimiterIdentity, Long> held : waiter.heldSince.entrySet()) {
            heldNanos.put(held.getKey(), now - held.getValue());
            decrementHeld(held.getKey());
        }
        for (Demand.Entry<?> entry : entries) {
            Long since = waiter.heldSince.get(entry.getKey());
            long amount = LimiterEvents.amountOf(entry.getAmounts());
            if (since != null) {
                emitter.emit(entry.getKey(), waiter.snapshot, heldCount(entry.getKey()), LimiterEvent.Type.GRANTED_FROM_HOLD, now - since, null, amount);
            }
            else {
                emitter.emit(entry.getKey(), waiter.snapshot, heldCount(entry.getKey()), LimiterEvent.Type.GRANTED_IMMEDIATE, 0, null, amount);
            }
        }
        Long memorySince = waiter.heldSince.get(memoryKey);
        if (memorySince != null) {
            emitter.emit(memoryKey, waiter.snapshot, heldCount(memoryKey), LimiterEvent.Type.GRANTED_FROM_HOLD, now - memorySince, null, 1);
        }
        waiter.complete(new Grant(waiter.demand, waiter.snapshot, now - waiter.arrivalNanos, heldNanos));
        return Outcome.GRANTED;
    }

    private static <T> boolean fits(Demand.Entry<T> entry, Waiter head) {
        return entry.getLimiter().fits(entry.getAmounts(), reservedAhead(entry, head));
    }

    private static <T> boolean tryTake(Demand.Entry<T> entry, Waiter head) {
        return entry.getLimiter().tryTake(entry.getAmounts(), reservedAhead(entry, head));
    }

    private static <T> Long earliestFit(Demand.Entry<T> entry, Waiter head) {
        return entry.getLimiter().earliestFit(entry.getAmounts(), reservedAhead(entry, head));
    }

    private static <T> List<T> reservedAhead(Demand.Entry<T> entry, Waiter head) {
        return head == null ? Collections.emptyList() : head.demand.amountsOn(entry);
    }

    /** Compensation for a grant attempt that did not complete. A {@code give} that throws is a defect and propagates. */
    private void giveBackAll(List<Demand.Entry<?>> taken) {
        for (Demand.Entry<?> entry : taken) {
            give(entry);
        }
    }

    /**
     * Records that an account refused the waiter and emits HELD once per account per admission:
     * a later refusal on the same account while the job still waits emits nothing and leaves the
     * count alone, so N waiters short on one account raise its count by exactly N.
     */
    private void markHeld(Waiter waiter, LimiterIdentity key, long now, long amount) {
        if (waiter.heldSince.putIfAbsent(key, now) == null) {
            int count = heldCounts.merge(key, 1, Integer::sum);
            if (key == memoryKey) {
                memoryHeld = count;
            }
            emitter.emit(key, waiter.snapshot, count, LimiterEvent.Type.HELD, 0, null, amount);
        }
    }

    /** Refuses one waiter because an account threw while it was being evaluated. */
    private void refuse(Waiter waiter, LimiterIdentity refusing, Throwable cause, long now) {
        String reason = cause instanceof LLMReadableRuntimeException ? "circuit_blocked" : "aborted";
        if (!waiter.heldSince.containsKey(refusing)) {
            emitter.emit(refusing, waiter.snapshot, heldCount(refusing), LimiterEvent.Type.REJECTED, 0, reason, 1);
        }
        rejectHeld(waiter, reason, now);
        RuntimeException failure = cause instanceof LLMReadableRuntimeException readable
                ? readable
                : new UncorrectableRuntimeLLMException(refusing.limiterName() + " failed during admission: " + cause.getMessage(), cause);
        if (reason.equals("aborted")) {
            log.error("Account {} threw during admission of job {}: {}", refusing.limiterName(), waiter.context.getJobId(), cause.getMessage());
            log.error(cause.getMessage(), cause);
        }
        waiter.fail(failure);
    }

    /** Emits REJECTED on every account the waiter was held on and balances the counts. */
    private void rejectHeld(Waiter waiter, String reason, long now) {
        for (Map.Entry<LimiterIdentity, Long> held : waiter.heldSince.entrySet()) {
            decrementHeld(held.getKey());
            emitter.emit(held.getKey(), waiter.snapshot, heldCount(held.getKey()), LimiterEvent.Type.REJECTED, now - held.getValue(), reason, 1);
        }
        waiter.heldSince.clear();
    }

    /** Balances one HELD, at grant or at rejection, so every count an event carries is the waiters held on that account after the transition. */
    private void decrementHeld(LimiterIdentity key) {
        heldCounts.merge(key, -1, Integer::sum);
        if (heldCounts.get(key) <= 0) {
            heldCounts.remove(key);
        }
        if (key == memoryKey) {
            memoryHeld = heldCount(key);
        }
    }

    private int heldCount(LimiterIdentity key) {
        return heldCounts.getOrDefault(key, 0);
    }

    private static final class Waiter {
        private final Demand demand;
        private final JobContext<?> context;
        private final Thread thread;
        private final JobSnapshot snapshot;
        private final long arrivalNanos;
        private final Map<LimiterIdentity, Long> heldSince = new LinkedHashMap<>();

        /**
         * Whether the last attempt held this waiter on memory. While the head of the queue is,
         * nobody behind it takes from memory in the same pass: memory reads the clock and the
         * heap afresh on every question, so a delay that closes or a latch that clears midway
         * down the queue would otherwise release whichever waiter happened to be asked at that
         * instant instead of the head.
         */
        private boolean memoryShort;
        private Long deadline;
        private volatile Grant grant;
        private volatile RuntimeException failure;

        Waiter(Demand demand, JobContext<?> context, Thread thread, long arrivalNanos) {
            this.demand = demand;
            this.context = context;
            this.thread = thread;
            this.snapshot = context.getSnapshot();
            this.arrivalNanos = arrivalNanos;
        }

        void complete(Grant granted) {
            grant = granted;
            LockSupport.unpark(thread);
        }

        void fail(RuntimeException cause) {
            failure = cause;
            LockSupport.unpark(thread);
        }

        void wakeSelf() {
            LockSupport.unpark(thread);
        }
    }
}
