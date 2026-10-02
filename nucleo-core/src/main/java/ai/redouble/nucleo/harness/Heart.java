/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import ai.redouble.nucleo.events.heartbeat.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.observability.*;
import org.slf4j.*;

import java.lang.reflect.*;
import java.time.*;
import java.util.concurrent.locks.*;
import java.util.function.*;

/**
 * The heartbeat engine: consumes framework-minted {@link Heartbeat}s off the bus into
 * the {@link HeartbeatStore}, and fires due entries as ordinary job submissions under
 * the authority captured at schedule time. Two virtual threads by design - the bus's
 * per-subscriber queue thread does only fast in-memory store work, and the dispatch
 * thread parks until the next due instant ({@code LockSupport.parkUntil}), unparked by
 * the store's change notification. Spurious wakes cost one peek and a re-park.
 *
 * <p>Catch-up semantics: slots are fixed from the original anchor, so after a pause
 * (GC, laptop sleep, suspended container) every overdue slot fires back-to-back. The
 * burst is deliberate - a silently skipped slot would be behavior an operator has to
 * reverse-engineer; the burst is self-explanatory in the HeartbeatFired timestamps.
 *
 * <p>Fires go through the REAL submission door: the captured {@link
 * ai.redouble.nucleo.guardrails.ScopeGuard} plays the caller-guard role in the same
 * judgment-and-merge every live submission passes, so a fired flow cannot hold wider
 * scope than its scheduler. A fire failure publishes {@link HeartbeatFireFailed} and,
 * for recurring entries, still enqueues the next instance - one bad fire never kills a
 * cadence. A Heart that is stopping drops every request and cancellation it is handed,
 * with a warning naming the message: the store is closing and a late schedule would
 * outlive it.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-29)
 */
public final class Heart extends AbstractStoppable implements JobObserver<HeartbeatEvent> {
    private static final Logger log = LoggerFactory.getLogger(Heart.class);
    /** The one setter a heartbeat's payload is applied through: the job's typed input. */
    private static final String INPUT_SETTER = "setInput";
    private final HeartbeatStore store;
    private final JobDispatcher dispatcher;
    private final Thread dispatchThread;

    Heart(HeartbeatStore store, JobDispatcher dispatcher) {
        this.store = store;
        this.dispatcher = dispatcher;
        this.dispatchThread = Thread.ofVirtual().name("heart-dispatch").unstarted(this::dispatchLoop);
        store.onChange(() -> LockSupport.unpark(dispatchThread));
        this.dispatchThread.start();
    }

    /** The store, for operator inspection ({@code inspect()}) and cancellation surfaces. */
    public HeartbeatStore getStore() {
        return store;
    }

    @Override
    public Predicate<HeartbeatEvent> getPredicate() {
        return event -> event instanceof HeartbeatRequested || event instanceof CancelHeartbeat;
    }

    @Override
    public void observe(HeartbeatEvent event) {
        if (isStopping()) {
            log.warn("Heart is stopping - dropping {}", event.message());
            return;
        }
        if (event instanceof HeartbeatRequested requested) {
            Heartbeat heartbeat = requested.heartbeat();
            store.enqueue(heartbeat).ifPresentOrElse(
                    displaced -> dispatcher.publishEvent(new HeartbeatRebound(displaced, heartbeat)),
                    () -> dispatcher.publishEvent(new HeartbeatScheduled(heartbeat)));
        }
        else if (event instanceof CancelHeartbeat cancel) {
            store.cancel(cancel.heartbeatId());
        }
    }

    private void dispatchLoop() {
        while (!isStopping()) {
            Instant target = store.peekNextRunAt().orElse(null);
            if (target == null) {
                LockSupport.park();
            }
            else if (target.isAfter(Instant.now())) {
                LockSupport.parkUntil(target.toEpochMilli());
            }
            if (isStopping()) {
                return;
            }
            fireDueHeartbeats(Instant.now());
        }
    }

    private void fireDueHeartbeats(Instant now) {
        Heartbeat due;
        while ((due = store.pollDue(now).orElse(null)) != null) {
            try {
                String jobId = fire(due);
                dispatcher.publishEvent(new HeartbeatFired(due, jobId));
            }
            catch (Exception e) {
                log.error("Heartbeat {} failed to fire: {}", due.getHeartbeatId(), e.getMessage());
                log.error(e.getMessage(), e);
                dispatcher.publishEvent(new HeartbeatFireFailed(due, e));
            }
            if (due.getRecurrence() instanceof Recurrence.FixedInterval(Duration period)) {
                // CAS: a rebind that landed between poll and here wins over the recurrence
                store.reEnqueueIfAbsent(due.next(due.getRunAt().plus(period)));
            }
        }
    }

    /**
     * Constructs and submits the fired job: fresh workflow root under the captured
     * principal, optional input applied through its typed setter (loud on mismatch,
     * per the schedule-time contract), conversation identity delivered ONLY through
     * {@link ConversationCarrier} - implementing the interface IS the claim of adoption
     * behavior, so a coincidentally-named setter can never silently swallow the
     * continuity obligation - and submission through the door with the captured guard.
     * <p>
     * Hydration happens inside the fired job's own execution (the carrier adopts by
     * id), keeping this dispatch thread free of I/O. The trade an operator should
     * know: a slow hydration shows up inside the fired job's wall clock, not as a
     * separate load job's run.
     */
    private String fire(Heartbeat heartbeat) throws Exception {
        Identifiable root = Job.workflow(heartbeat.getUserId(), "heartbeat-" + heartbeat.getHeartbeatId());
        Job<?> job = construct(heartbeat.getJobClass(), root);
        if (heartbeat.getInput() != null) {
            applyInput(job, heartbeat.getInput(), heartbeat);
        }
        if (heartbeat.getConversationId() != null) {
            if (!(job instanceof ConversationCarrier carrier)) {
                throw new InvalidInputException("conversationId", heartbeat.getConversationId(),
                        "heartbeat " + heartbeat.getHeartbeatId() + " names a conversation, but "
                                + job.getClass().getSimpleName() + " does not implement ConversationCarrier - "
                                + "only a declared carrier may claim conversation continuity");
            }
            carrier.setConversationId(heartbeat.getConversationId());
        }
        JobHandle<?> handle = dispatcher.submitFired(job, heartbeat.getScopeGuard());
        return handle.getJobId();
    }

    private Job<?> construct(Class<? extends Job<?>> jobClass, Identifiable root) throws Exception {
        try {
            Constructor<? extends Job<?>> ctor = jobClass.getConstructor(Identifiable.class);
            return ctor.newInstance(root);
        }
        catch (NoSuchMethodException e) {
            throw new InvalidInputException("jobClass", jobClass.getName(),
                    "a heartbeat-firable job must expose a public (Identifiable) constructor");
        }
    }

    /**
     * The input contract: the value is applied through the job's own typed setter,
     * failing loudly when no setter accepts the value's type - a schedule carrying a
     * payload the job cannot receive is a misdeclaration, never a silent drop.
     */
    private void applyInput(Job<?> job, Object value, Heartbeat heartbeat) throws Exception {
        for (Method method : job.getClass().getMethods()) {
            if (method.getName().equals(INPUT_SETTER) && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isAssignableFrom(value.getClass())) {
                method.invoke(job, value);
                return;
            }
        }
        throw new InvalidInputException(INPUT_SETTER, value.getClass().getSimpleName(),
                "heartbeat " + heartbeat.getHeartbeatId() + " carries a value that " + job.getClass().getSimpleName()
                        + " cannot accept - no " + INPUT_SETTER + " taking " + value.getClass().getSimpleName());
    }

    @Override
    protected void doStop() {
        LockSupport.unpark(dispatchThread);
    }
}
