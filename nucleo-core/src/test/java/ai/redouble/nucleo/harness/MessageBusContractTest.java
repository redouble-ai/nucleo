/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.harness.observability.*;
import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the "Nothing Blocks the Producer" contract of {@link LinkedQueueMessageBus}:
 * publish returns without waiting on any subscriber, a slow subscriber never starves a
 * fast one, routing honors workflow scoping and sealed-category (interface) subscription,
 * and stale subscriptions are reaped instead of accumulating. And the bus's lifecycle: a
 * message before start or after stop is dropped, what was published before stop is delivered
 * before stop returns, stop on a bus that is not running or a
 * second time throws, subscribe while stopping throws, a duplicate observer instance gets a
 * no-op subscription, and an observer that throws keeps receiving.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-31)
 */
class MessageBusContractTest {

    private LinkedQueueMessageBus bus;

    @BeforeEach
    void freshBus() {
        bus = new LinkedQueueMessageBus();
        bus.start();
    }

    @AfterEach
    void stopBus() {
        bus.stop();
    }

    static class RecordingObserver<T extends JobEvent> implements JobObserver<T> {
        final List<T> seen = new CopyOnWriteArrayList<>();
        final long sleepMs;
        volatile boolean stale;

        RecordingObserver() {
            this(0);
        }

        RecordingObserver(long sleepMs) {
            this.sleepMs = sleepMs;
        }

        @Override
        public void observe(T event) {
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            seen.add(event);
        }

        @Override
        public boolean isStale() {
            return stale;
        }
    }

    private static JobSnapshot snapshot(String jobId, String workflowId) {
        return new JobSnapshot(jobId, null, workflowId, "test-user", Job.class, JobType.TOOL,
                "fixture", null, null, JobState.RUNNING, 1, Instant.now(), Instant.now(), null,
                List.of(), Map.of());
    }

    private static JobStartedEvent started(String jobId, String workflowId) {
        return new JobStartedEvent(snapshot(jobId, workflowId), 1);
    }

    private static void awaitCount(List<?> seen, int expected, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (seen.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(expected, seen.size(), what);
    }

    @Test
    void publishNeverWaitsOnSubscribers_andASlowOneNeverStarvesAFastOne() throws Exception {
        RecordingObserver<JobEvent> slow = new RecordingObserver<>(200);
        RecordingObserver<JobEvent> fast = new RecordingObserver<>();
        bus.subscribe(Job.class, JobEvent.class, slow);
        bus.subscribe(Job.class, JobEvent.class, fast);

        int events = 20;
        long start = System.nanoTime();
        for (int i = 0; i < events; i++) {
            bus.publish(started("job-" + i, "wf-nonblocking"));
        }
        long publishMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(publishMillis < 1_000,
                "publishing 20 events must not absorb the slow subscriber's 4s of sleep; took " + publishMillis + "ms");
        awaitCount(fast.seen, events, "the fast subscriber drains everything while the slow one lags");
        assertTrue(slow.seen.size() < events,
                "the slow subscriber is genuinely still working on its own queue");
    }

    @Test
    void workflowScopedSubscriptionSeesOnlyItsWorkflow() throws Exception {
        RecordingObserver<JobStartedEvent> scoped = new RecordingObserver<>();
        bus.subscribe(Job.class, JobStartedEvent.class, "wf-mine", scoped);

        bus.publish(started("a", "wf-mine"));
        bus.publish(started("b", "wf-other"));
        bus.publish(started("c", "wf-mine"));

        awaitCount(scoped.seen, 2, "exactly the two events of the subscribed workflow arrive");
        assertTrue(scoped.seen.stream().allMatch(e -> "wf-mine".equals(e.snapshot().getWorkflowId())));
    }

    @Test
    void categorySubscriptionSpansImplementors_andExcludesOtherCategories() throws Exception {
        RecordingObserver<LifecycleEvent> lifecycle = new RecordingObserver<>();
        bus.subscribe(Job.class, LifecycleEvent.class, lifecycle);

        bus.publish(started("a", "wf-cat"));
        bus.publish(new WorkflowCompleteEvent(snapshot("a", "wf-cat")));
        bus.publish(started("b", "wf-cat"));

        awaitCount(lifecycle.seen, 2, "a sealed-category subscription receives every implementor and nothing else");
        assertTrue(lifecycle.seen.stream().allMatch(e -> e instanceof JobStartedEvent),
                "the terminal event never crosses the category boundary");
    }

    @Test
    void aDuplicateObserverInstanceGetsANoOpSubscription() throws Exception {
        RecordingObserver<JobEvent> once = new RecordingObserver<>();
        MessageBus.Subscription first = bus.subscribe(Job.class, JobEvent.class, once);
        MessageBus.Subscription second = bus.subscribe(Job.class, JobEvent.class, once);
        assertNotEquals("noop", first.getId());
        assertEquals("noop", second.getId(), "the same instance subscribed again is refused with a no-op subscription");
        bus.publish(started("a", "wf-dup"));
        awaitCount(once.seen, 1, "the observer receives each event once");
        Thread.sleep(100);
        assertEquals(1, once.seen.size(), "not twice");
    }

    @Test
    void aMessageBeforeStartOrAfterStopIsDropped() throws Exception {
        LinkedQueueMessageBus own = new LinkedQueueMessageBus();
        RecordingObserver<JobEvent> observer = new RecordingObserver<>();
        own.subscribe(Job.class, JobEvent.class, observer);
        own.publish(started("early", "wf-lifecycle"));
        own.start();
        own.start();
        own.publish(started("live", "wf-lifecycle"));
        awaitCount(observer.seen, 1, "only the message published while running arrives");
        assertEquals("live", observer.seen.get(0).snapshot().getJobId(), "the early one was dropped, not deferred");
        own.stop();
        assertDoesNotThrow(() -> own.publish(started("late", "wf-lifecycle")), "publishing after stop is a silent drop");
        Thread.sleep(100);
        assertEquals(1, observer.seen.size());
    }

    @Test
    void stopDeliversWhatWasQueuedBeforeItReturns() {
        LinkedQueueMessageBus own = new LinkedQueueMessageBus();
        own.start();
        RecordingObserver<JobEvent> slow = new RecordingObserver<>() {
            @Override
            public void observe(JobEvent event) {
                try {
                    Thread.sleep(50);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                super.observe(event);
            }
        };
        own.subscribe(Job.class, JobEvent.class, slow);
        for (int i = 0; i < 10; i++) {
            own.publish(started("job-" + i, "wf-drain"));
        }
        own.stop();
        assertEquals(10, slow.seen.size(), "stop drains each queue before it returns, as MessageBus.stop promises");
    }

    @Test
    void anUnsubscribedObserverReceivesItsBacklog_andTheUnsubscribeNeverBlocks() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        RecordingObserver<JobEvent> gated = new RecordingObserver<>() {
            @Override
            public void observe(JobEvent event) {
                try {
                    gate.await(10, TimeUnit.SECONDS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                super.observe(event);
            }
        };
        MessageBus.Subscription subscription = bus.subscribe(Job.class, JobEvent.class, gated);
        for (int i = 0; i < 10; i++) {
            bus.publish(started("queued-" + i, "wf-unsub-drain"));
        }
        long before = System.currentTimeMillis();
        subscription.unsubscribe();
        long elapsed = System.currentTimeMillis() - before;
        assertTrue(elapsed < 1_000, "unsubscribe returned while the observer was still parked on the first event: " + elapsed + " ms");
        assertEquals(0, gated.seen.size(), "the observer is still blocked, nothing delivered yet");
        bus.publish(started("late", "wf-unsub-drain"));
        gate.countDown();
        awaitCount(gated.seen, 10, "everything enqueued before the unsubscribe is delivered, never interrupted away");
        Thread.sleep(100);
        assertEquals(10, gated.seen.size(), "what was published after the unsubscribe never arrives");
        assertTrue(gated.seen.stream().noneMatch(e -> "late".equals(e.snapshot().getJobId())));
    }

    @Test
    void stopAndSubscribeRefuseTheWrongLifecycleState() {
        LinkedQueueMessageBus own = new LinkedQueueMessageBus();
        assertThrows(IllegalStateException.class, own::stop, "stop on a bus that never started");
        own.start();
        own.stop();
        assertThrows(IllegalStateException.class, own::stop, "a second stop");
        assertThrows(IllegalStateException.class, () -> own.subscribe(Job.class, JobEvent.class, new RecordingObserver<>()), "subscribe while stopping");
    }

    @Test
    void anObserverThatThrowsIsLoggedAndKeepsReceiving() throws Exception {
        RecordingObserver<JobEvent> flaky = new RecordingObserver<>() {
            @Override
            public void observe(JobEvent event) {
                if ("first".equals(event.snapshot().getJobId())) {
                    throw new IllegalStateException("fixture observer fails once");
                }
                super.observe(event);
            }
        };
        bus.subscribe(Job.class, JobEvent.class, flaky);
        bus.publish(started("first", "wf-flaky"));
        bus.publish(started("second", "wf-flaky"));
        awaitCount(flaky.seen, 1, "the event after the failure still arrives");
        assertEquals("second", flaky.seen.get(0).snapshot().getJobId());
    }

    @Test
    void staleSubscriptionsAreReaped_andStopReceiving() throws Exception {
        RecordingObserver<JobEvent> goesStale = new RecordingObserver<>();
        RecordingObserver<JobEvent> staysFresh = new RecordingObserver<>();
        bus.subscribe(Job.class, JobEvent.class, goesStale);
        bus.subscribe(Job.class, JobEvent.class, staysFresh);

        bus.publish(started("before", "wf-stale"));
        awaitCount(goesStale.seen, 1, "both alive before staleness");

        goesStale.stale = true;
        int removed = bus.cleanupStaleSubscriptions();
        assertEquals(1, removed, "exactly the stale subscription is reaped");

        bus.publish(started("after", "wf-stale"));
        awaitCount(staysFresh.seen, 2, "the fresh subscriber keeps receiving");
        assertEquals(1, goesStale.seen.size(), "the reaped subscriber never sees another event");
    }
}
