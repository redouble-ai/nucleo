/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.events;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.observability.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;

import static ai.redouble.nucleo.events.EventFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * How the events' scoping rules play out on the real bus: an event with no owning job (a
 * system event, a heartbeat transition, a limiter transition without a job) reaches global
 * and by-type subscriptions for all jobs, never a job-type-scoped or a workflow-scoped one; a subscription to a
 * sealed category receives every member of it and nothing outside it; the uncategorized chat
 * event reaches only subscribers that ask for it by type or for everything.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class EventDeliveryScopeTest {

    /** A job type no fixture snapshot claims, so a subscription to it is a narrower scope than Job. */
    interface NarrowJob extends Job<Void> {
    }

    static class Recording<T extends JobEvent> implements JobObserver<T> {
        final List<T> seen = new CopyOnWriteArrayList<>();

        @Override
        public void observe(T event) {
            seen.add(event);
        }
    }

    private LinkedQueueMessageBus bus;

    @BeforeEach
    void start() {
        bus = new LinkedQueueMessageBus();
        bus.start();
    }

    @AfterEach
    void stop() {
        bus.stop();
    }

    private static void settle(Recording<?> marker, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (marker.seen.size() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }

    @Test
    void anEventWithNoOwningJobReachesGlobalAndByTypeSubscriptionsNeverScopedOnes() throws InterruptedException {
        Recording<JobEvent> global = new Recording<>();
        Recording<ai.redouble.nucleo.events.heartbeat.HeartbeatEvent> byType = new Recording<>();
        Recording<ai.redouble.nucleo.events.heartbeat.HeartbeatEvent> byTypeAndJobType = new Recording<>();
        Recording<ai.redouble.nucleo.events.heartbeat.HeartbeatEvent> byTypeAndWorkflow = new Recording<>();
        Recording<JobEvent> byJobType = new Recording<>();
        Recording<JobEvent> byWorkflow = new Recording<>();
        Recording<JobStartedEvent> marker = new Recording<>();
        bus.subscribe(Job.class, JobEvent.class, global);
        bus.subscribe(Job.class, ai.redouble.nucleo.events.heartbeat.HeartbeatEvent.class, byType);
        bus.subscribe(NarrowJob.class, ai.redouble.nucleo.events.heartbeat.HeartbeatEvent.class, byTypeAndJobType);
        bus.subscribe(Job.class, ai.redouble.nucleo.events.heartbeat.HeartbeatEvent.class, "wf-1", byTypeAndWorkflow);
        bus.subscribe(NarrowJob.class, JobEvent.class, byJobType);
        bus.subscribe(Job.class, JobEvent.class, "wf-1", byWorkflow);
        bus.subscribe(Job.class, JobStartedEvent.class, marker);
        bus.publish(new SchedulerEvent(SchedulerEvent.Type.STARTED));
        bus.publish(new ai.redouble.nucleo.events.heartbeat.CancelHeartbeat("hb-1"));
        bus.publish(new LimiterEvent(null, java.time.Instant.now(), "memory", "memory", 1, 0, 0, LimiterEvent.Type.RELEASED, 0, null, null, 1));
        bus.publish(new JobStartedEvent(snapshot("j", "wf-1"), 1));
        // every subscription has its own queue and its own thread: a count is settled on the
        // subscription it is asserted on, never inferred from another's
        settle(marker, 1);
        settle(global, 4);
        settle(byType, 1);
        settle(byWorkflow, 1);
        assertEquals(4, global.seen.size(), "a global subscription sees the three job-less events and the job event");
        assertEquals(1, byType.seen.size(), "a by-type subscription for all jobs sees a job-less event of its type, the way a Stethoscope hears heartbeats");
        assertEquals(0, byTypeAndJobType.seen.size(), "a by-type subscription scoped to a job type never sees an event with no owning job");
        assertEquals(0, byTypeAndWorkflow.seen.size(), "a by-type subscription scoped to a workflow never sees an event with no owning job");
        assertEquals(0, byJobType.seen.size(), "a job-type-scoped subscription never sees an event with no owning job");
        assertEquals(1, byWorkflow.seen.size(), "a workflow-scoped subscription sees only its workflow's job events");
        assertInstanceOf(JobStartedEvent.class, byWorkflow.seen.get(0));
    }

    @Test
    void aCategorySubscriptionSeesEveryMemberAndNothingOutsideIt() throws InterruptedException {
        Recording<LifecycleEvent> lifecycle = new Recording<>();
        Recording<MessageCompleteEvent> marker = new Recording<>();
        bus.subscribe(Job.class, LifecycleEvent.class, lifecycle);
        bus.subscribe(Job.class, MessageCompleteEvent.class, marker);
        JobSnapshot s = snapshot("j", "wf-1");
        bus.publish(new JobScheduled(s));
        bus.publish(new JobStartedEvent(s, 1));
        bus.publish(new JobProgressEvent<>(s, "p", 50));
        bus.publish(new WorkflowCompleteEvent(s));
        bus.publish(new OrchestratorIdleEvent(s, List.of(), Map.of()));
        bus.publish(new JobCompletedEvent<>(s, "r", 1, null, null));
        bus.publish(new MessageCompleteEvent(s, true));
        settle(marker, 1);
        settle(lifecycle, 4);
        assertEquals(4, lifecycle.seen.size(), "scheduled, started, idle and completed are lifecycle; progress and workflow completion are not");
        assertTrue(lifecycle.seen.stream().noneMatch(WorkflowTerminationEvent.class::isInstance), "workflow termination is parallel to lifecycle, not inside it");
    }

    @Test
    void theUncategorizedChatEventReachesOnlyThoseWhoAskForItOrForEverything() throws InterruptedException {
        Recording<JobEvent> everything = new Recording<>();
        Recording<MessageCompleteEvent> byType = new Recording<>();
        Recording<LifecycleEvent> lifecycle = new Recording<>();
        Recording<ProgressEvent> progress = new Recording<>();
        bus.subscribe(Job.class, JobEvent.class, everything);
        bus.subscribe(Job.class, MessageCompleteEvent.class, byType);
        bus.subscribe(Job.class, LifecycleEvent.class, lifecycle);
        bus.subscribe(Job.class, ProgressEvent.class, progress);
        bus.publish(new MessageCompleteEvent(snapshot("j", "wf-1"), true));
        settle(byType, 1);
        settle(everything, 1);
        assertEquals(1, byType.seen.size());
        assertEquals(1, everything.seen.size());
        assertEquals(0, lifecycle.seen.size(), "no category subscription sees the uncategorized event");
        assertEquals(0, progress.seen.size());
    }
}
