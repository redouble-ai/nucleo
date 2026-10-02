/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.events.retry.*;
import ai.redouble.nucleo.guardrails.*;
import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.http.*;
import ai.redouble.nucleo.harness.errors.retry.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.observability.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One attempt, in the order the dispatch contract states: {@code preExecute} runs once with
 * the resources already held; input guards run after the dependencies and before the
 * resources, output guards after release, and an output refusal turns the success into a
 * failure; a guard refused at the internal door fails the gated job with a
 * {@code SystemException}, an executed guard's refusal is the refusal; models are resolved and
 * priced before acquisition and the spend gates judge last; a transactional job sees begin,
 * commit and await in that order, rollback on a failure, and a failed rollback as suppressed;
 * a success signals the limiter before its permit returns; each correction re-runs the attempt
 * with its own started event and the fifth is refused; a truncation re-runs once and a second
 * is refused saying which; a root's events are scheduled, started, completed and workflow
 * complete, or scheduled, started, failed and workflow failed, with the handle settled last on
 * both paths; an {@code Error} thrown by a job reaches the
 * caller as a {@code SystemException}; {@code cancelWorkflow} cancels the workflow's running
 * jobs; a handle's {@code cancel(boolean)} is the cooperative cancel; a job taken out of a
 * queue is settled as cancelled; a workflow observer registered on a handle sees its workflow
 * only and stops with the handle.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class KernelExecutionContractTest {

    @BeforeAll
    static void startDispatcher() {
        JobDispatcher.getInstance().start();
    }

    private static Identifiable root() {
        return Job.workflow("execution-contract-test", "execution-contract-test");
    }

    /** Always-fitting account, so a job holds a resource without any real capacity. */
    static class TokenAccount extends AbstractRateLimiter<Object> {
        @Override
        public boolean fits(List<Object> mine, List<Object> reservedAhead) {
            return true;
        }

        @Override
        public boolean tryTake(List<Object> mine, List<Object> reservedAhead) {
            return true;
        }

        @Override
        public void give(List<Object> amounts) {
        }

        @Override
        public Long earliestFit(List<Object> mine, List<Object> reservedAhead) {
            return null;
        }

        @Override
        public String limiterName() {
            return "token-account";
        }

        @Override
        public String limiterCategory() {
            return "semaphore";
        }

        @Override
        public long capacity() {
            return 1;
        }

        @Override
        public long currentInUse() {
            return 0;
        }

        @Override
        public void onSuccess() {
        }

        @Override
        public void onRateLimitError(UpstreamFailure failure) {
        }

        @Override
        public RateLimiterStatus getStatus() {
            return () -> "token-account";
        }
    }

    static class HookedJob extends AbstractJob<String> {
        final AtomicInteger preExecuteCalls = new AtomicInteger();
        final AtomicBoolean heldResourcesAtPreExecute = new AtomicBoolean();
        final AtomicBoolean postExecuteSawResult = new AtomicBoolean();

        HookedJob(Identifiable parent) {
            super(parent, "hooked");
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = new JobRequirements();
            req.requireRateLimiter(new TokenAccount(), null);
            return req;
        }

        @Override
        public void preExecute(JobContext<String> context) {
            preExecuteCalls.incrementAndGet();
            heldResourcesAtPreExecute.set(JobContext.isExecutingJobWithResources());
        }

        @Override
        public void postExecute(JobContext<String> context) {
            postExecuteSawResult.set(context.getResult().isPresent());
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            return "hooked";
        }
    }

    /** A provider that records every call the dispatcher makes on it. */
    static class RecordingProvider implements DBResourceProvider<Object> {
        final List<String> calls = new CopyOnWriteArrayList<>();
        private final DatabaseGate gate = new DatabaseGate("recording-fixture", 2);

        @Override
        public String name() {
            return "recording-fixture";
        }

        @Override
        public RateLimiter<Void> admission() {
            return gate;
        }

        @Override
        public DBManagedResource<Object> acquire(boolean readOnly) {
            calls.add("acquire");
            return () -> "handle";
        }

        @Override
        public void begin(DBManagedResource<Object> resource) {
            calls.add("begin");
        }

        @Override
        public void commit(DBManagedResource<Object> resource) {
            calls.add("commit");
        }

        @Override
        public void awaitCompletion(DBManagedResource<Object> resource) {
            calls.add("await");
        }

        @Override
        public void rollback(DBManagedResource<Object> resource) {
            calls.add("rollback");
        }

        @Override
        public void close(DBManagedResource<Object> resource) {
            calls.add("close");
        }

        @Override
        public void abort(DBManagedResource<Object> resource) {
            calls.add("abort");
        }
    }

    static class TransactionalJob extends AbstractJob<String> {
        private final RecordingProvider provider;
        private final boolean fail;

        TransactionalJob(Identifiable parent, RecordingProvider provider, boolean fail) {
            super(parent, "transactional");
            this.provider = provider;
            this.fail = fail;
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = new JobRequirements();
            req.addProvider(provider);
            req.setRequiresTransaction(true);
            return req;
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            if (fail) {
                throw new IllegalStateException("work failed");
            }
            return "committed";
        }
    }

    static class ErrorThrowingJob extends AbstractJob<String> {
        ErrorThrowingJob(Identifiable parent) {
            super(parent, "erroring");
        }

        @Override
        public JobRequirements getRequirements() {
            return new JobRequirements();
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            throw new StackOverflowError("fixture error");
        }
    }

    static class ObliviousJob extends AbstractJob<String> {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch mayFinish = new CountDownLatch(1);

        ObliviousJob(Identifiable parent) {
            super(parent, "oblivious");
        }

        @Override
        public JobRequirements getRequirements() {
            return new JobRequirements();
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) throws Exception {
            started.countDown();
            mayFinish.await(10, TimeUnit.SECONDS);
            return "finished anyway";
        }
    }

    static class QuickJob extends AbstractJob<String> {
        QuickJob(Identifiable parent) {
            super(parent, "quick");
        }

        @Override
        public JobRequirements getRequirements() {
            return new JobRequirements();
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            return "quick";
        }
    }

    /** A provider whose rollback fails, so the failed cleanup rides as suppressed on the job's failure. */
    static final class FailingRollbackProvider extends RecordingProvider {
        @Override
        public void rollback(DBManagedResource<Object> resource) {
            super.rollback(resource);
            throw new IllegalStateException("rollback failed on a dead connection");
        }
    }

    /** Asks for corrections a given number of times, then answers. */
    static class CorrectingJob extends AbstractJob<String> {
        private final int corrections;
        final AtomicInteger executions = new AtomicInteger();

        CorrectingJob(Identifiable parent, int corrections) {
            super(parent, "correcting");
            this.corrections = corrections;
        }

        @Override
        public JobRequirements getRequirements() {
            return new JobRequirements();
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            int execution = executions.incrementAndGet();
            if (execution <= corrections) {
                throw new ResponseCorrectionRetryException("fixture-model", execution, new IllegalStateException("malformed output " + execution));
            }
            return "corrected";
        }
    }

    /** Reports a truncation on each of the given models, one per attempt, then answers. */
    static class TruncatingJob extends AbstractJob<String> {
        private final List<String> truncatingModels;
        final AtomicInteger executions = new AtomicInteger();

        TruncatingJob(Identifiable parent, List<String> truncatingModels) {
            super(parent, "truncating");
            this.truncatingModels = truncatingModels;
        }

        @Override
        public JobRequirements getRequirements() {
            return new JobRequirements();
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            int execution = executions.incrementAndGet();
            if (execution <= truncatingModels.size()) {
                throw new OutputTruncationRetryException(truncatingModels.get(execution - 1), 1000, 4000);
            }
            return "fits";
        }
    }

    /** Records the order of what admission and the dispatcher do to it. */
    static final class OrderRecordingLimiter extends TokenAccount {
        final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        public boolean tryTake(List<Object> mine, List<Object> reservedAhead) {
            events.add("take");
            return true;
        }

        @Override
        public void give(List<Object> amounts) {
            events.add("give");
        }

        @Override
        public void onSuccess() {
            events.add("onSuccess");
        }

        @Override
        public String limiterName() {
            return "order-recording";
        }
    }

    static class SignalledJob extends AbstractJob<String> {
        private final OrderRecordingLimiter limiter;

        SignalledJob(Identifiable parent, OrderRecordingLimiter limiter) {
            super(parent, "signalled");
            this.limiter = limiter;
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = new JobRequirements();
            req.requireRateLimiter(limiter, null);
            return req;
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            return "signalled";
        }
    }

    /** A gate of one, so the fixtures can read whether the job holds its permit. */
    static final class ProbeGate extends CountingGate {
        ProbeGate() {
            super(1);
        }

        @Override
        public String limiterName() {
            return "probe-gate";
        }
    }

    /** A guarded leaf holding one permit; records what it and its guards observe, in order. */
    static class GuardedJob extends AbstractJob<String> implements GuardedExecution {
        final ProbeGate gate = new ProbeGate();
        final List<ContentGuardrail<?>> guards = new ArrayList<>();
        final List<String> observed = new CopyOnWriteArrayList<>();
        final JobHandle<?> dependency;
        private final String input;
        private final String answer;

        GuardedJob(Identifiable parent, JobHandle<?> dependency, String input, String answer) {
            super(parent, "guarded");
            this.dependency = dependency;
            this.input = input;
            this.answer = answer;
        }

        @Override
        public Object guardedInputTarget() {
            return input;
        }

        @Override
        public List<ContentGuardrail<?>> declareContentGuardrails() {
            return List.copyOf(guards);
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = new JobRequirements();
            req.requireRateLimiter(gate, null);
            return req;
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            observed.add("execute holding=" + gate.currentInUse());
            return answer;
        }
    }

    /** Records where it ran relative to the gated job's dependency and permit; refuses a marked target. */
    static class ProbeGuard extends AbstractContentGuardrail<Object> {
        private final GuardedJob gated;
        private final Direction direction;

        ProbeGuard(GuardedJob gated, Direction direction) {
            super(gated);
            this.gated = gated;
            this.direction = direction;
        }

        @Override
        public Direction direction() {
            return direction;
        }

        @Override
        public Class<Object> targetType() {
            return Object.class;
        }

        @Override
        public void validate(Object target) throws GuardrailException {
            String dependencyState = gated.dependency == null ? "none" : String.valueOf(gated.dependency.getContext().getState());
            gated.observed.add(direction.name().toLowerCase() + "-guard holding=" + gated.gate.currentInUse() + " dependency=" + dependencyState);
            if (String.valueOf(target).contains("refuse")) {
                throw new GuardrailException(direction.name().toLowerCase() + " refused by fixture");
            }
        }
    }

    /** A guard carrying a tenant claim of its own, judged at the internal door before it runs. */
    static class TenantClaimingGuard extends AbstractContentGuardrail<Object> implements TenantScoped {
        private final String tenant;
        final AtomicBoolean ran = new AtomicBoolean();

        TenantClaimingGuard(Identifiable parent, String tenant) {
            super(parent);
            this.tenant = tenant;
        }

        @Override
        public String getTenantId() {
            return tenant;
        }

        @Override
        public Direction direction() {
            return Direction.INPUT;
        }

        @Override
        public Class<Object> targetType() {
            return Object.class;
        }

        @Override
        public void validate(Object target) {
            ran.set(true);
        }
    }

    /** An orchestrator sealed to one tenant that submits the child it is handed and reports the outcome's shape. */
    static class SealedParent extends AbstractJob<String> implements ScopeAuthority, TenantScoped {
        private final String tenant;
        private ScopeGuard guard;
        Job<String> child;

        SealedParent(Identifiable parent, String tenant) {
            super(parent, "sealed-parent");
            this.tenant = tenant;
        }

        @Override
        public String getTenantId() {
            return tenant;
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

        @Override
        public String execute(JobResources resources, JobContext<String> context) throws Exception {
            try {
                return JobDispatcher.getInstance().submit(child).get();
            }
            catch (ExecutionException failure) {
                Throwable cause = failure.getCause();
                return cause.getClass().getSimpleName() + " caused by " + (cause.getCause() == null ? "nothing" : cause.getCause().getClass().getSimpleName());
            }
        }
    }

    /** A job declaring a model need and holding one permit, so the spend gate can see when it is consulted. */
    static class PricedJob extends AbstractJob<String> {
        final ProbeGate gate = new ProbeGate();

        PricedJob(Identifiable parent) {
            super(parent, "priced");
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = new JobRequirements();
            req.requireModel(Grade.SMALL, Depth.STANDARD, 100, OutputDeclaration.of(50));
            req.requireRateLimiter(gate, null);
            return req;
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            return "priced";
        }
    }

    /** Registered once on the dispatcher; records what it is consulted with for the jobs it is told to watch. */
    static final class ProbeSpendGate implements SpendGate {
        final Map<String, PricedJob> watched = new ConcurrentHashMap<>();
        final Map<String, String> observed = new ConcurrentHashMap<>();

        @Override
        public void admit(JobContext<?> context, List<ModelBinding> priced) {
            PricedJob job = watched.get(context.getJobId());
            if (job == null) {
                return;
            }
            ModelBinding binding = priced.get(0);
            observed.put(context.getJobId(), "holding=" + job.gate.currentInUse() + " resolved=" + binding.isResolved()
                    + " reservation=" + binding.getReservation());
        }
    }

    private static final ProbeSpendGate SPEND_GATE = new ProbeSpendGate();

    @BeforeAll
    static void registerSpendGate() {
        JobDispatcher.getInstance().registerSpendGate(SPEND_GATE);
    }

    static class EventSink implements JobObserver<JobEvent> {
        final List<JobEvent> seen = new CopyOnWriteArrayList<>();

        @Override
        public void observe(JobEvent event) {
            seen.add(event);
        }

        List<Class<?>> classesFor(String jobId) {
            List<Class<?>> classes = new ArrayList<>();
            for (JobEvent event : seen) {
                if (jobId.equals(event.snapshot().getJobId())) {
                    classes.add(event.getClass());
                }
            }
            return classes;
        }
    }

    private static void awaitEvents(EventSink sink, String jobId, int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && sink.classesFor(jobId).size() < count) {
            Thread.sleep(20);
        }
    }

    private static JobCancelledException cancellationIn(Throwable outcome) {
        for (Throwable t = outcome; t != null; t = t.getCause()) {
            if (t instanceof JobCancelledException cancelled) {
                return cancelled;
            }
        }
        return null;
    }

    @Test
    void preExecuteRunsOnceWithResourcesHeld_andPostExecuteSeesTheResult() throws Exception {
        HookedJob job = new HookedJob(root());
        assertEquals("hooked", JobDispatcher.getInstance().submit(job).get());
        assertEquals(1, job.preExecuteCalls.get(), "one attempt, one preExecute");
        assertTrue(job.heldResourcesAtPreExecute.get(), "preExecute runs after the resources are held");
        assertTrue(job.postExecuteSawResult.get(), "postExecute runs after the result is recorded");
    }

    @Test
    void aTransactionalJobBeginsCommitsAndAwaitsInOrder_andRollsBackOnFailure() throws Exception {
        RecordingProvider ok = new RecordingProvider();
        assertEquals("committed", JobDispatcher.getInstance().submit(new TransactionalJob(root(), ok, false)).get());
        assertEquals(List.of("acquire", "begin", "commit", "await", "close"), ok.calls, "the transaction lifecycle around a successful execute");
        RecordingProvider failing = new RecordingProvider();
        assertThrows(ExecutionException.class, () -> JobDispatcher.getInstance().submit(new TransactionalJob(root(), failing, true)).get());
        assertEquals(List.of("acquire", "begin", "rollback", "close"), failing.calls, "a failing execute is rolled back, never committed");
    }

    @Test
    void aRootsEventsRunScheduledStartedCompletedThenWorkflowComplete() throws Exception {
        Identifiable root = root();
        EventSink sink = new EventSink();
        MessageBus.Subscription subscription = JobDispatcher.getInstance().observeWorkflow(root.getWorkflowId(), sink);
        try {
            QuickJob job = new QuickJob(root);
            JobDispatcher.getInstance().submit(job).get();
            awaitEvents(sink, job.getId(), 4);
            assertEquals(List.of(JobScheduled.class, JobStartedEvent.class, JobCompletedEvent.class, WorkflowCompleteEvent.class),
                    sink.classesFor(job.getId()), "the four events of a successful root, in order");
            WorkflowCompleteEvent complete = (WorkflowCompleteEvent) sink.seen.stream()
                    .filter(e -> e instanceof WorkflowCompleteEvent && job.getId().equals(e.snapshot().getJobId())).findFirst().orElseThrow();
            assertTrue(complete.isSuccessful(), "the workflow event of a success says so");
        }
        finally {
            subscription.unsubscribe();
        }
    }

    @Test
    void aFailingRootsEventsRunScheduledStartedFailedThenWorkflowFailed() throws Exception {
        Identifiable root = root();
        EventSink sink = new EventSink();
        MessageBus.Subscription subscription = JobDispatcher.getInstance().observeWorkflow(root.getWorkflowId(), sink);
        try {
            ErrorThrowingJob job = new ErrorThrowingJob(root);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> JobDispatcher.getInstance().submit(job).get());
            awaitEvents(sink, job.getId(), 4);
            assertEquals(List.of(JobScheduled.class, JobStartedEvent.class, JobFailedEvent.class, WorkflowCompleteEvent.class),
                    sink.classesFor(job.getId()), "the four events of a failed root, in order");
            WorkflowCompleteEvent complete = (WorkflowCompleteEvent) sink.seen.stream()
                    .filter(e -> e instanceof WorkflowCompleteEvent && job.getId().equals(e.snapshot().getJobId())).findFirst().orElseThrow();
            assertFalse(complete.isSuccessful(), "the workflow event of a failure says so");
            assertInstanceOf(SystemException.class, failure.getCause(), "an Error thrown by a job is a system error of the job: " + failure);
            assertInstanceOf(StackOverflowError.class, failure.getCause().getCause(), "with the Error as its cause");
            assertEquals(JobState.FAILED, JobState.valueOf("FAILED"));
        }
        finally {
            subscription.unsubscribe();
        }
    }

    @Test
    void cancelWorkflowCancelsTheWorkflowsRunningJobs() throws Exception {
        Identifiable root = root();
        ObliviousJob job = new ObliviousJob(root);
        JobHandle<String> handle = JobDispatcher.getInstance().submit(job);
        assertTrue(job.started.await(5, TimeUnit.SECONDS));
        assertEquals(1, JobDispatcher.getInstance().cancelWorkflow(root.getWorkflowId(), "workflow abandoned"), "one running job in the workflow");
        assertEquals(0, JobDispatcher.getInstance().cancelWorkflow("no-such-workflow", "nothing"), "an unknown workflow cancels nothing");
        job.mayFinish.countDown();
        ExecutionException outcome = assertThrows(ExecutionException.class, handle::get);
        assertNotNull(cancellationIn(outcome), "the caller sees the cancellation: " + outcome);
    }

    @Test
    void aHandlesFutureStyleCancelIsTheCooperativeCancel() throws Exception {
        ObliviousJob job = new ObliviousJob(root());
        JobHandle<String> handle = JobDispatcher.getInstance().submit(job);
        assertTrue(job.started.await(5, TimeUnit.SECONDS));
        assertTrue(handle.cancel(true), "the flag carries no meaning; the running job is cancelled cooperatively");
        job.mayFinish.countDown();
        ExecutionException outcome = assertThrows(ExecutionException.class, handle::get);
        assertNotNull(cancellationIn(outcome), outcome.toString());
        assertFalse(handle.cancel(false), "a settled job is no longer found queued or running");
    }

    @Test
    void aWorkflowObserverOnAHandleSeesItsWorkflowOnlyAndStopsWithTheHandle() throws Exception {
        Identifiable mine = root();
        Identifiable other = root();
        ObliviousJob job = new ObliviousJob(mine);
        JobHandle<String> handle = JobDispatcher.getInstance().submit(job);
        EventSink sink = new EventSink();
        handle.registerWorkflowObserver(sink);
        assertTrue(job.started.await(5, TimeUnit.SECONDS));
        QuickJob stranger = new QuickJob(other);
        JobDispatcher.getInstance().submit(stranger).get();
        job.mayFinish.countDown();
        assertEquals("finished anyway", handle.get());
        // All four of the job's events reach the observer: the handle settles after the workflow event
        awaitEvents(sink, job.getId(), 4);
        assertTrue(sink.seen.stream().allMatch(e -> mine.getWorkflowId().equals(e.snapshot().getWorkflowId())),
                "only the handle's workflow reaches the observer");
        assertFalse(sink.seen.stream().anyMatch(e -> stranger.getId().equals(e.snapshot().getJobId())));
        int seenAtCompletion = sink.seen.size();
        QuickJob later = new QuickJob(mine);
        JobDispatcher.getInstance().submit(later).get();
        Thread.sleep(200);
        assertFalse(sink.seen.stream().anyMatch(e -> later.getId().equals(e.snapshot().getJobId())),
                "the observer was unsubscribed when the handle settled");
        assertEquals(seenAtCompletion, sink.seen.size());
    }

    @Test
    void aRootSuccessSettlesTheHandleAfterItsWorkflowCompleteEvent() throws Exception {
        ObliviousJob job = new ObliviousJob(root());
        JobHandle<String> handle = JobDispatcher.getInstance().submit(job);
        EventSink handleSink = new EventSink();
        handle.registerWorkflowObserver(handleSink);
        assertTrue(job.started.await(5, TimeUnit.SECONDS));
        job.mayFinish.countDown();
        assertEquals("finished anyway", handle.get());
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline
                && handleSink.seen.stream().noneMatch(WorkflowCompleteEvent.class::isInstance)) {
            Thread.sleep(20);
        }
        assertTrue(handleSink.seen.stream().anyMatch(WorkflowCompleteEvent.class::isInstance),
                "the handle settles last on every path, so its own observer sees the workflow's terminal event");
    }

    @Test
    void aRootFailureSettlesTheHandleAfterItsWorkflowCompleteEvent() throws Exception {
        GatedFailingJob job = new GatedFailingJob(root());
        JobHandle<String> handle = JobDispatcher.getInstance().submit(job);
        EventSink handleSink = new EventSink();
        handle.registerWorkflowObserver(handleSink);
        assertTrue(job.started.await(5, TimeUnit.SECONDS));
        job.mayFail.countDown();
        assertThrows(ExecutionException.class, handle::get);
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline
                && handleSink.seen.stream().noneMatch(WorkflowCompleteEvent.class::isInstance)) {
            Thread.sleep(20);
        }
        assertTrue(handleSink.seen.stream().anyMatch(e -> e instanceof WorkflowCompleteEvent complete && !complete.isSuccessful()),
                "the handle settles last on the failure path too, after the workflow's failed terminal event");
        assertTrue(handleSink.seen.stream().anyMatch(JobFailedEvent.class::isInstance),
                "the job's own terminal event was published before the settle");
    }

    /** Waits to be released, then fails with a readable refusal: the failure-path twin of {@link ObliviousJob}. */
    static class GatedFailingJob extends AbstractJob<String> {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch mayFail = new CountDownLatch(1);

        GatedFailingJob(Identifiable parent) {
            super(parent, "gated-failing");
        }

        @Override
        public JobRequirements getRequirements() {
            return new JobRequirements();
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) throws Exception {
            started.countDown();
            mayFail.await(10, TimeUnit.SECONDS);
            throw new InvalidInputException("query", "", "must not be blank");
        }
    }

    @Test
    void aFailedRollbackRidesAsSuppressedOnTheJobsOwnFailure() {
        FailingRollbackProvider provider = new FailingRollbackProvider();
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> JobDispatcher.getInstance().submit(new TransactionalJob(root(), provider, true)).get());
        assertInstanceOf(IllegalStateException.class, failure.getCause(), "the job's own failure is the story: " + failure);
        assertEquals("work failed", failure.getCause().getMessage());
        assertEquals(List.of("acquire", "begin", "rollback", "close"), provider.calls, "the rollback was attempted and the handle still closed");
        Throwable[] suppressed = failure.getCause().getSuppressed();
        assertEquals(1, suppressed.length, "the failed rollback is attached, never the replacement");
        assertInstanceOf(UncorrectableRuntimeLLMException.class, suppressed[0], "rollbackAll's own wrapper");
        assertEquals(1, suppressed[0].getSuppressed().length);
        assertTrue(suppressed[0].getSuppressed()[0].getMessage().contains("rollback failed"), "carrying the provider's failure");
    }

    @Test
    void aSuccessSignalsTheLimiterBeforeItsPermitReturns() throws Exception {
        OrderRecordingLimiter limiter = new OrderRecordingLimiter();
        assertEquals("signalled", JobDispatcher.getInstance().submit(new SignalledJob(root(), limiter)).get());
        assertEquals(List.of("take", "onSuccess", "give"), limiter.events, "onSuccess reaches the limiter while the job still holds it");
    }

    @Test
    void eachCorrectionReRunsTheAttemptWithItsOwnStartedEvent_andTheFifthIsRefused() throws Exception {
        Identifiable root = root();
        EventSink sink = new EventSink();
        MessageBus.Subscription subscription = JobDispatcher.getInstance().observeWorkflow(root.getWorkflowId(), sink);
        try {
            CorrectingJob twice = new CorrectingJob(root, 2);
            assertEquals("corrected", JobDispatcher.getInstance().submit(twice).get());
            assertEquals(3, twice.executions.get(), "two corrections, three executions");
            awaitEvents(sink, twice.getId(), 8);
            assertEquals(3, sink.classesFor(twice.getId()).stream().filter(JobStartedEvent.class::equals).count(),
                    "every attempt publishes its own JobStartedEvent");
            assertEquals(List.of(JobScheduled.class, JobStartedEvent.class, ResponseCorrectionRetryEvent.class, JobStartedEvent.class,
                            ResponseCorrectionRetryEvent.class, JobStartedEvent.class, JobCompletedEvent.class, WorkflowCompleteEvent.class),
                    sink.classesFor(twice.getId()), "each correction is announced, then the attempt starts again");
            CorrectingJob fiveTimes = new CorrectingJob(root, 5);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> JobDispatcher.getInstance().submit(fiveTimes).get());
            assertEquals(5, fiveTimes.executions.get(), "the backstop lets four corrections through and refuses the fifth");
            assertInstanceOf(UncorrectableRuntimeLLMException.class, failure.getCause(), "past the backstop the job is looping, not converging: " + failure);
            assertTrue(failure.getCause().getMessage().contains("Exceeded max response correction retries (4)"), failure.getCause().getMessage());
            assertInstanceOf(ResponseCorrectionRetryException.class, failure.getCause().getCause(), "with the last signal as cause");
        }
        finally {
            subscription.unsubscribe();
        }
    }

    @Test
    void aTruncationReRunsOnce_andASecondIsRefusedSayingWhich() throws Exception {
        TruncatingJob once = new TruncatingJob(root(), List.of("model-a"));
        assertEquals("fits", JobDispatcher.getInstance().submit(once).get(), "one escalation, then the answer");
        assertEquals(2, once.executions.get());
        TruncatingJob rebuilt = new TruncatingJob(root(), List.of("model-a", "model-a"));
        ExecutionException rebuiltFailure = assertThrows(ExecutionException.class, () -> JobDispatcher.getInstance().submit(rebuilt).get());
        assertEquals(2, rebuilt.executions.get(), "the second truncation ends the execution");
        assertInstanceOf(UncorrectableRuntimeLLMException.class, rebuiltFailure.getCause(), rebuiltFailure.toString());
        assertTrue(rebuiltFailure.getCause().getMessage().contains("rebuilt its conversation"), "same model twice: the escalation never reached the wire: " + rebuiltFailure.getCause().getMessage());
        TruncatingJob failedOver = new TruncatingJob(root(), List.of("model-a", "model-b"));
        ExecutionException failoverFailure = assertThrows(ExecutionException.class, () -> JobDispatcher.getInstance().submit(failedOver).get());
        assertInstanceOf(UncorrectableRuntimeLLMException.class, failoverFailure.getCause());
        assertTrue(failoverFailure.getCause().getMessage().contains("after failover from model-a"), "another model: the answer does not fit there either: " + failoverFailure.getCause().getMessage());
    }

    @Test
    void inputGuardsRunAfterDependenciesAndBeforeResources_outputGuardsAfterRelease() throws Exception {
        Identifiable root = root();
        JobHandle<String> dependency = JobDispatcher.getInstance().submit(new QuickJob(root));
        GuardedJob job = new GuardedJob(root, dependency, "plain input", "plain answer");
        job.guards.add(new ProbeGuard(job, ContentGuardrail.Direction.INPUT));
        job.guards.add(new ProbeGuard(job, ContentGuardrail.Direction.OUTPUT));
        assertEquals("plain answer", JobDispatcher.getInstance().submit(job, dependency).get());
        assertEquals(List.of("input-guard holding=0 dependency=COMPLETED", "execute holding=1", "output-guard holding=0 dependency=COMPLETED"), job.observed,
                "the input guard sees the finished dependency and no permit; execute holds the permit; the output guard runs after release");
    }

    @Test
    void anOutputGuardRefusalTurnsTheSuccessIntoAFailure() throws Exception {
        Identifiable root = root();
        EventSink sink = new EventSink();
        MessageBus.Subscription subscription = JobDispatcher.getInstance().observeWorkflow(root.getWorkflowId(), sink);
        try {
            GuardedJob job = new GuardedJob(root, null, "plain input", "please refuse this");
            job.guards.add(new ProbeGuard(job, ContentGuardrail.Direction.OUTPUT));
            ExecutionException failure = assertThrows(ExecutionException.class, () -> JobDispatcher.getInstance().submit(job).get());
            assertTrue(job.observed.contains("execute holding=1"), "the job ran to its answer");
            assertInstanceOf(GuardrailException.class, failure.getCause(), "an executed guard's refusal is the refusal: " + failure);
            awaitEvents(sink, job.getId(), 6);
            assertEquals(List.of(JobScheduled.class, JobStartedEvent.class, JobFailedEvent.class, WorkflowCompleteEvent.class),
                    sink.classesFor(job.getId()).stream().filter(c -> c != LimiterEvent.class).toList(),
                    "the caller never sees the answer; the root failed (the permit's own LimiterEvents aside)");
        }
        finally {
            subscription.unsubscribe();
        }
    }

    @Test
    void aDoorRefusedGuardIsASystemError_anExecutedGuardsRefusalIsTheRefusal() throws Exception {
        Identifiable root = root();
        SealedParent parent = new SealedParent(root, "t1");
        GuardedJob gated = new GuardedJob(parent, null, "plain input", "answer");
        TenantClaimingGuard drifted = new TenantClaimingGuard(gated, "t9");
        gated.guards.add(drifted);
        parent.child = gated;
        assertEquals("SystemException caused by GuardrailException", JobDispatcher.getInstance().submit(parent).get(),
                "a guard refused at the internal door rendered no verdict: the gated job fails with a system error carrying the refusal");
        assertFalse(drifted.ran.get(), "the drifted guard never ran");
        assertTrue(gated.observed.isEmpty(), "and the gated job never ran");
        SealedParent honest = new SealedParent(root, "t1");
        GuardedJob refused = new GuardedJob(honest, null, "refuse this input", "answer");
        refused.guards.add(new ProbeGuard(refused, ContentGuardrail.Direction.INPUT));
        honest.child = refused;
        assertEquals("GuardrailException caused by nothing", JobDispatcher.getInstance().submit(honest).get(),
                "an executed guard's refusal reaches the caller as itself");
        assertEquals(List.of("input-guard holding=0 dependency=none"), refused.observed, "the input guard ran and the job never did");
    }

    @Test
    void modelsAreResolvedAndPricedBeforeAcquisition_andTheSpendGatesJudgeLast() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> JobDispatcher.getInstance().registerSpendGate(null), "a null gate is refused");
        PricedJob job = new PricedJob(root());
        SPEND_GATE.watched.put(job.getId(), job);
        assertEquals("priced", JobDispatcher.getInstance().submit(job).get());
        String observed = SPEND_GATE.observed.get(job.getId());
        assertNotNull(observed, "the registered gate was consulted for the attempt");
        assertTrue(observed.startsWith("holding=0 "), "consulted before any permit is held: " + observed);
        assertTrue(observed.contains("resolved=true"), "with the binding resolved: " + observed);
        assertFalse(observed.endsWith("reservation=0"), "and priced: " + observed);
    }

    @Test
    void aJobTakenOutOfAQueueIsSettledAsCancelled() throws Exception {
        Identifiable root = root();
        EventSink sink = new EventSink();
        MessageBus.Subscription subscription = JobDispatcher.getInstance().observeWorkflow(root.getWorkflowId(), sink);
        try {
            QuickJob job = new QuickJob(root);
            JobDispatcher.QueuedJob<String> queued = new JobDispatcher.QueuedJob<>(job);
            JobContext<String> context = new JobContext<>(job, "execution-contract-test", job.getTimeout(), job.getRequirements());
            JobHandle<String> handle = new JobHandle<>(context);
            queued.setContext(context);
            queued.setJobHandle(handle);
            JobDispatcher.getInstance().settleCancelledWhileQueued(queued, "Cancelled while queued: fixture");
            assertEquals(JobState.CANCELLED, context.getState(), "the state is settled by the remover, since the job never runs");
            ExecutionException outcome = assertThrows(ExecutionException.class, () -> handle.get(1, TimeUnit.SECONDS));
            JobCancelledException cancelled = cancellationIn(outcome);
            assertNotNull(cancelled, "the waiter wakes with the cancellation a running job's cancel produces: " + outcome);
            assertTrue(cancelled.getCause().getMessage().contains("Cancelled while queued"), cancelled.getCause().getMessage());
            awaitEvents(sink, job.getId(), 2);
            assertEquals(List.of(JobCancelled.class, WorkflowCompleteEvent.class), sink.classesFor(job.getId()),
                    "the terminal event is the cancellation, and a root's workflow closes here as on every other path");
            JobEvent event = sink.seen.stream().filter(e -> job.getId().equals(e.snapshot().getJobId())).findFirst().orElseThrow();
            assertEquals("Cancelled while queued: fixture", event.message(),
                    "a job cancelled while queued carries the remover's message, not the event's own \"Job cancelled\"");
            WorkflowCompleteEvent workflow = sink.seen.stream().filter(WorkflowCompleteEvent.class::isInstance)
                    .map(WorkflowCompleteEvent.class::cast).findFirst().orElseThrow();
            assertFalse(workflow.isSuccessful(), "a workflow whose root never ran did not succeed");
        }
        finally {
            subscription.unsubscribe();
        }
    }
}
