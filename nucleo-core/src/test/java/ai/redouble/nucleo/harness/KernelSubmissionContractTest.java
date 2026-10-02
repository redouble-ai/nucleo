/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.guardrails.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.observability.*;
import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The submission door and the routing behind it: a non-orchestrator submitting from inside
 * its execution is refused with a handle born failed and a {@code JobFailedEvent} in place of
 * a {@code JobScheduled}; a live job may be named as parent only from its own thread, and
 * never from outside any job; a scoped child outside the caller's sealed scope is refused with
 * the guard's own {@code GuardrailException}; a submission from inside a job stamps the
 * caller's class; a dependency list that closes a cycle is refused at submit;
 * {@code getRequirements()} is invoked once at submit and once per attempt; initial metadata
 * is on the context before {@code JobScheduled} fires; the priority queue serves the higher
 * priority first and submission order among equals; {@code submitWithDelay} submits after the
 * delay; a second {@code start()} is a no-op.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class KernelSubmissionContractTest {

    @BeforeAll
    static void startDispatcher() {
        JobDispatcher.getInstance().start();
    }

    private static Identifiable root() {
        return Job.workflow("submission-contract-test", "submission-contract-test");
    }

    static class QuickJob extends AbstractJob<String> {
        final AtomicInteger requirementsCalls = new AtomicInteger();

        QuickJob(Identifiable parent) {
            super(parent, "quick");
        }

        @Override
        public JobRequirements getRequirements() {
            requirementsCalls.incrementAndGet();
            return new JobRequirements();
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            return "quick";
        }
    }

    /** A plain job, not an orchestrator, that tries to submit a child from inside its execution. */
    static class SubmittingLeaf extends AbstractJob<String> {
        SubmittingLeaf(Identifiable parent) {
            super(parent, "submitting-leaf");
        }

        @Override
        public JobRequirements getRequirements() {
            return new JobRequirements();
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) throws Exception {
            JobHandle<String> child = JobDispatcher.getInstance().submit(new QuickJob(this));
            try {
                return child.get();
            }
            catch (ExecutionException refused) {
                return refused.getCause().getClass().getSimpleName() + ": " + refused.getCause().getMessage();
            }
        }
    }

    /** Runs until released, so a test can name it as a parent while it is live. */
    static class ParkedJob extends AbstractJob<String> {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        ParkedJob(Identifiable parent) {
            super(parent, "parked");
        }

        @Override
        public JobRequirements getRequirements() {
            return new JobRequirements();
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) throws Exception {
            started.countDown();
            release.await(10, TimeUnit.SECONDS);
            return "released";
        }
    }

    /** The least an orchestrator is: a job that may submit, with a guard the door seals. */
    abstract static class Orchestrator extends AbstractJob<String> implements ScopeAuthority {
        private ScopeGuard guard;

        Orchestrator(Identifiable parent, String prefix) {
            super(parent, prefix);
        }

        @Override
        public JobRequirements getRequirements() {
            return new JobRequirements();
        }

        @Override
        public ScopeGuard getScopeGuard() {
            return guard;
        }

        @Override
        public void setScopeGuard(ScopeGuard guard) {
            this.guard = guard;
        }

        @Override
        public void sealScopeGuard(ScopeGuard effective) {
            this.guard = effective;
        }

        /** Submits the child and answers its result, or the refusal's type and message. */
        static String outcomeOf(Job<String> child) throws InterruptedException {
            try {
                return JobDispatcher.getInstance().submit(child).get();
            }
            catch (ExecutionException refused) {
                return refused.getCause().getClass().getSimpleName() + ": " + refused.getCause().getMessage();
            }
        }
    }

    /** From its own thread, names another live job as the parent of its child. */
    static class IntrudingOrchestrator extends Orchestrator {
        private final Job<?> otherLiveJob;

        IntrudingOrchestrator(Identifiable parent, Job<?> otherLiveJob) {
            super(parent, "intruding");
            this.otherLiveJob = otherLiveJob;
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) throws Exception {
            return outcomeOf(new QuickJob(otherLiveJob));
        }
    }

    /** Answers what the dispatcher stamped on its context under obs.caller_class. */
    static class MetadataEchoJob extends AbstractJob<String> {
        MetadataEchoJob(Identifiable parent) {
            super(parent, "metadata-echo");
        }

        @Override
        public JobRequirements getRequirements() {
            return new JobRequirements();
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            return String.valueOf(context.getMetadata(GuardrailEnforcer.OBS_CALLER_CLASS));
        }
    }

    static class EchoingOrchestrator extends Orchestrator {
        EchoingOrchestrator(Identifiable parent) {
            super(parent, "echoing");
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) throws Exception {
            return outcomeOf(new MetadataEchoJob(this));
        }
    }

    /** A leaf carrying a tenant claim, judged at the door against its caller's sealed guard. */
    static class TenantChild extends QuickJob implements TenantScoped {
        private final String tenant;

        TenantChild(Identifiable parent, String tenant) {
            super(parent);
            this.tenant = tenant;
        }

        @Override
        public String getTenantId() {
            return tenant;
        }
    }

    /** Bound to one tenant by its own claim, sealed at its dispatch; submits a child claiming another. */
    static class TenantOrchestrator extends Orchestrator implements TenantScoped {
        private final String ownTenant;
        private final String childTenant;

        TenantOrchestrator(Identifiable parent, String ownTenant, String childTenant) {
            super(parent, "tenant-orchestrator");
            this.ownTenant = ownTenant;
            this.childTenant = childTenant;
        }

        @Override
        public String getTenantId() {
            return ownTenant;
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) throws Exception {
            return outcomeOf(new TenantChild(this, childTenant));
        }
    }

    static class EventSink implements JobObserver<JobEvent> {
        final List<JobEvent> seen = new CopyOnWriteArrayList<>();

        @Override
        public void observe(JobEvent event) {
            seen.add(event);
        }

        boolean sawAny(Class<?> type) {
            return seen.stream().anyMatch(type::isInstance);
        }
    }

    private static void awaitSettled(JobHandle<?> handle) {
        try {
            handle.get(10, TimeUnit.SECONDS);
        }
        catch (Exception ignored) {
            // the outcome is read through the events and the handle's chain by the caller
        }
    }

    @Test
    void aNonOrchestratorSubmittingFromInsideIsRefusedWithABornFailedHandle() throws Exception {
        String outcome = JobDispatcher.getInstance().submit(new SubmittingLeaf(root())).get();
        assertTrue(outcome.startsWith("SystemException: "), "the refusal is the door's own type: " + outcome);
        assertTrue(outcome.contains("Only orchestrators may submit"), outcome);
    }

    @Test
    void aRefusedSubmissionPublishesAFailureAndNeverASchedule() throws Exception {
        Identifiable root = root();
        EventSink sink = new EventSink();
        MessageBus.Subscription subscription = JobDispatcher.getInstance().observeWorkflow(root.getWorkflowId(), sink);
        try {
            ParkedJob parked = new ParkedJob(root);
            JobHandle<String> parkedHandle = JobDispatcher.getInstance().submit(parked);
            assertTrue(parked.started.await(5, TimeUnit.SECONDS));
            // From outside any job, naming the live job as parent is the second wall
            QuickJob intruder = new QuickJob(parked);
            JobHandle<String> refused = JobDispatcher.getInstance().submit(intruder);
            ExecutionException failure = assertThrows(ExecutionException.class, refused::get);
            assertInstanceOf(SystemException.class, failure.getCause(), "the refusal is a SystemException: " + failure);
            assertTrue(failure.getCause().getMessage().contains("may only create new roots"), failure.getCause().getMessage());
            parked.release.countDown();
            awaitSettled(parkedHandle);
            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline && !sink.seen.stream()
                    .anyMatch(e -> e instanceof JobFailedEvent && intruder.getId().equals(e.snapshot().getJobId()))) {
                Thread.sleep(20);
            }
            assertTrue(sink.seen.stream().anyMatch(e -> e instanceof JobFailedEvent && intruder.getId().equals(e.snapshot().getJobId())),
                    "a refused submission is a real terminal event");
            assertFalse(sink.seen.stream().anyMatch(e -> e instanceof JobScheduled && intruder.getId().equals(e.snapshot().getJobId())),
                    "a refused job was never scheduled");
        }
        finally {
            subscription.unsubscribe();
        }
    }

    @Test
    void aDependencyCycleIsRefusedAtSubmit() throws Exception {
        Identifiable root = root();
        QuickJob first = new QuickJob(root);
        JobHandle<String> firstHandle = JobDispatcher.getInstance().submit(first);
        JobHandle<String> second = JobDispatcher.getInstance().submit(new QuickJob(root), firstHandle);
        firstHandle.get();
        second.get();
        IllegalArgumentException cycle = assertThrows(IllegalArgumentException.class,
                () -> JobDispatcher.getInstance().submit(first, second),
                "the first job depending on a job that depends on it closes a cycle");
        assertTrue(cycle.getMessage().contains("Cycle detected"), cycle.getMessage());
        assertTrue(cycle.getMessage().contains(first.getId()), "the message names the cycle's jobs: " + cycle.getMessage());
    }

    @Test
    void requirementsAreCapturedOnceAtSubmitAndOncePerAttempt() throws Exception {
        QuickJob job = new QuickJob(root());
        JobDispatcher.getInstance().submit(job).get();
        assertEquals(2, job.requirementsCalls.get(), "one capture at submit, one for the single attempt, nothing else");
    }

    @Test
    void initialMetadataIsOnTheSnapshotBeforeTheScheduleEvent() throws Exception {
        Identifiable root = root();
        EventSink sink = new EventSink();
        MessageBus.Subscription subscription = JobDispatcher.getInstance().observeWorkflow(root.getWorkflowId(), sink);
        try {
            JobHandle<String> handle = JobDispatcher.getInstance().submit(new QuickJob(root), Map.of("seeded", "yes"));
            handle.get();
            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline && !sink.sawAny(JobScheduled.class)) {
                Thread.sleep(20);
            }
            JobEvent scheduled = sink.seen.stream().filter(JobScheduled.class::isInstance).findFirst().orElseThrow();
            assertEquals("yes", scheduled.snapshot().getMetadata().get("seeded"), "the seed is visible on the very first event");
        }
        finally {
            subscription.unsubscribe();
        }
    }

    @Test
    void thePriorityQueueServesTheHigherPriorityFirstThenSubmissionOrder() throws Exception {
        Identifiable root = root();
        AbstractJob<String> low = new QuickJob(root) {
            @Override
            public int getPriority() {
                return 1;
            }
        };
        AbstractJob<String> high = new QuickJob(root) {
            @Override
            public int getPriority() {
                return 9;
            }
        };
        JobDispatcher.QueuedJob<String> lowFirst = new JobDispatcher.QueuedJob<>(low);
        JobDispatcher.QueuedJob<String> highLater = new JobDispatcher.QueuedJob<>(high);
        assertTrue(highLater.compareTo(lowFirst) < 0, "the higher Job.getPriority() is served first, as that method declares");
        JobDispatcher.QueuedJob<String> sameEarlier = new JobDispatcher.QueuedJob<>(new QuickJob(root));
        Thread.sleep(2);
        JobDispatcher.QueuedJob<String> sameLater = new JobDispatcher.QueuedJob<>(new QuickJob(root));
        assertTrue(sameEarlier.compareTo(sameLater) < 0, "equal priorities keep submission order: the earlier submission first");
        assertTrue(sameLater.compareTo(sameEarlier) > 0);
        assertTrue(highLater.compareTo(sameEarlier) < 0, "priority outranks submission order");
    }

    @Test
    void aLiveJobMayBeNamedAsParentOnlyFromItsOwnThread() throws Exception {
        ParkedJob parked = new ParkedJob(root());
        JobHandle<String> parkedHandle = JobDispatcher.getInstance().submit(parked);
        assertTrue(parked.started.await(5, TimeUnit.SECONDS));
        try {
            String outcome = JobDispatcher.getInstance().submit(new IntrudingOrchestrator(root(), parked)).get();
            assertTrue(outcome.startsWith("SystemException: "), "the second wall is a SystemException: " + outcome);
            assertTrue(outcome.contains("runs on the thread of"), outcome);
        }
        finally {
            parked.release.countDown();
            awaitSettled(parkedHandle);
        }
    }

    @Test
    void aSubmissionFromInsideAJobStampsTheCallersClass() throws Exception {
        String stamped = JobDispatcher.getInstance().submit(new EchoingOrchestrator(root())).get();
        assertEquals(EchoingOrchestrator.class.getName(), stamped, "the child's context carries the submitting job's class under obs.caller_class");
    }

    @Test
    void aScopedChildOutsideTheCallersSealedScopeIsRefusedWithTheGuardsException() throws Exception {
        TenantOrchestrator drifting = new TenantOrchestrator(root(), "t1", "t9");
        String outcome = JobDispatcher.getInstance().submit(drifting).get();
        assertTrue(outcome.startsWith("GuardrailException: "), "the scope wall refuses with the guard's own exception: " + outcome);
        assertTrue(outcome.contains("t9"), "naming the drifted claim: " + outcome);
        TenantOrchestrator inside = new TenantOrchestrator(root(), "t1", "t1");
        assertEquals("quick", JobDispatcher.getInstance().submit(inside).get(), "a child inside the sealed scope passes the wall");
    }

    @Test
    void submitWithDelaySubmitsAfterTheDelayAsARoot() throws Exception {
        long before = System.currentTimeMillis();
        CompletableFuture<JobHandle<String>> future = JobDispatcher.getInstance().submitWithDelay(new QuickJob(root()), Duration.ofMillis(300));
        JobHandle<String> handle = future.get(10, TimeUnit.SECONDS);
        assertTrue(System.currentTimeMillis() - before >= 300, "the submission waited the delay");
        assertEquals("quick", handle.get(), "the delayed job ran");
    }

    @Test
    void aSecondStartIsANoOp() {
        assertDoesNotThrow(() -> JobDispatcher.getInstance().start(), "start on a started dispatcher warns and returns");
        assertTrue(JobDispatcher.getInstance().isRunning());
        assertTrue(Governor.isRunning(), "the Governor reads the same dispatcher");
    }

    @Test
    void theGracePeriodIsTenPercentBoundedBetweenHalfASecondAndThirtySeconds() {
        assertEquals(Duration.ofMillis(500), JobDispatcher.calculateGracePeriod(Duration.ofSeconds(1)), "the floor");
        assertEquals(Duration.ofSeconds(10), JobDispatcher.calculateGracePeriod(Duration.ofSeconds(100)), "ten percent");
        assertEquals(Duration.ofSeconds(30), JobDispatcher.calculateGracePeriod(Duration.ofMinutes(10)), "the ceiling");
    }
}
