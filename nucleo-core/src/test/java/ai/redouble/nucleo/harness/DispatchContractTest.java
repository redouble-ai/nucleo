/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.http.*;
import ai.redouble.nucleo.tools.*;
import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the dispatch-path contracts around sequencing and signaling:
 *
 * <ul>
 *   <li><b>Dependency sequencing</b> - the prescribed fix for the no-waiting-while-holding
 *       rule: a dependent job starts with its dependency's result already resolved
 *       ({@code singleDependencyResult}), no blocking anywhere.</li>
 *   <li><b>Dependency failure</b> - a non-tolerant dependent fails with
 *       {@link DependencyFailedException} and never executes; a tolerant one executes and
 *       sees the failed dependency as a null result.</li>
 *   <li><b>Call-order ordinals</b> - a doer's sequential submits stamp 1, 2, ...; a fan-out
 *       shares one ordinal, so the trajectory tree is reconstructible.</li>
 *   <li><b>Limiter signaling</b> - a job failing with {@code ExternalServiceException}
 *       feeds an {@link UpstreamFailure} to its declared limiters (the exception-to-throttle
 *       bridge), and a succeeding job feeds them {@code onSuccess}.</li>
 * </ul>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-31)
 */
class DispatchContractTest {

    @BeforeAll
    static void startDispatcher() {
        JobDispatcher.getInstance().start();
    }

    private static Identifiable root() {
        return Job.workflow("dispatch-contract-test", "dispatch-contract-test");
    }

    static class ProducerJob extends AbstractJob<String> {
        private final boolean fail;

        ProducerJob(Identifiable parent, boolean fail) {
            super(parent, "producer-fixture");
            this.fail = fail;
        }

        @Override
        public JobRequirements getRequirements() {
            return new JobRequirements();
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            if (fail) {
                throw new IllegalStateException("producer fixture fails");
            }
            return "alpha";
        }
    }

    static class ConsumerJob extends AbstractJob<String> {
        private final boolean tolerant;
        final AtomicBoolean executed = new AtomicBoolean(false);

        ConsumerJob(Identifiable parent, boolean tolerant) {
            super(parent, "consumer-fixture");
            this.tolerant = tolerant;
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = new JobRequirements();
            req.setToleratesDependencyFailures(tolerant);
            return req;
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            executed.set(true);
            String upstream = context.singleDependencyResult();
            return upstream + ":consumed";
        }
    }

    @Test
    void dependentStartsWithTheDependencyResultAlreadyResolved() throws Exception {
        JobHandle<String> producer = JobDispatcher.getInstance().submit(new ProducerJob(root(), false));
        JobHandle<String> consumer = JobDispatcher.getInstance().submit(new ConsumerJob(root(), false), producer);
        assertEquals("alpha:consumed", consumer.get(),
                "the dependency's result reaches the dependent without any blocking call");
    }

    @Test
    void failedDependencyFailsTheDependent_whichNeverExecutes() {
        JobHandle<String> producer = JobDispatcher.getInstance().submit(new ProducerJob(root(), true));
        ConsumerJob consumer = new ConsumerJob(root(), false);
        JobHandle<String> handle = JobDispatcher.getInstance().submit(consumer, producer);
        ExecutionException failure = assertThrows(ExecutionException.class, handle::get);
        Throwable cause = failure;
        boolean found = false;
        for (; cause != null; cause = cause.getCause()) {
            if (cause instanceof DependencyFailedException) {
                found = true;
                break;
            }
        }
        assertTrue(found, "the dependent's failure names the dependency contract: " + failure);
        assertFalse(consumer.executed.get(), "a job whose dependency failed must never execute");
    }

    @Test
    void tolerantDependentExecutesAndSeesTheFailureAsNull() throws Exception {
        JobHandle<String> producer = JobDispatcher.getInstance().submit(new ProducerJob(root(), true));
        TolerantConsumer consumer = new TolerantConsumer(root());
        JobHandle<String> handle = JobDispatcher.getInstance().submit(consumer, producer);
        assertEquals("dependency-was-null", handle.get(),
                "a tolerant job runs anyway and decides for itself what a failed dependency means");
    }

    static class TolerantConsumer extends AbstractJob<String> {
        TolerantConsumer(Identifiable parent) {
            super(parent, "tolerant-consumer-fixture");
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = new JobRequirements();
            req.setToleratesDependencyFailures(true);
            return req;
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            Map<JobSnapshot, Object> results = context.getDependencyResults();
            Object only = results.values().iterator().next();
            return only == null ? "dependency-was-null" : "dependency-was-" + only;
        }
    }

    /** Child that reports the iteration ordinal its parent stamped on it. */
    static class OrdinalReporterJob extends AbstractJob<String> {
        private final String label;
        private final Map<String, Long> sink;

        OrdinalReporterJob(Identifiable parent, String label, Map<String, Long> sink) {
            super(parent, "ordinal-fixture");
            this.label = label;
            this.sink = sink;
        }

        @Override
        public JobRequirements getRequirements() {
            return new JobRequirements();
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            sink.put(label, (Long)context.getMetadata(ai.redouble.nucleo.tools.AbstractOrchestrator.META_ITERATION));
            return label;
        }
    }

    static class SteppingDoer extends AbstractDoer<String, String> {
        private final Map<String, Long> sink;

        SteppingDoer(Identifiable parent, Map<String, Long> sink) {
            super(parent, "stepping-doer-fixture");
            this.sink = sink;
        }

        @Override
        public String execute(JobContext<String> context) throws LLMReadableCheckedException {
            try {
                submitInStep(new OrdinalReporterJob(this, "a", sink)).get();
                submitInStep(new OrdinalReporterJob(this, "b", sink)).get();
                nextStep();
                JobHandle<String> c = submitInCurrentStep(new OrdinalReporterJob(this, "c", sink));
                JobHandle<String> d = submitInCurrentStep(new OrdinalReporterJob(this, "d", sink));
                c.get();
                d.get();
                return "stepped";
            }
            catch (Exception e) {
                throw LLMReadableCheckedException.unwrap(e);
            }
        }
    }

    @Test
    void sequentialSubmitsStampCallOrder_andFanOutSharesOneOrdinal() throws Exception {
        Map<String, Long> ordinals = new ConcurrentHashMap<>();
        SteppingDoer doer = new SteppingDoer(root(), ordinals);
        doer.setInput("x");
        assertEquals("stepped", JobDispatcher.getInstance().submit(doer).get());
        assertEquals(1L, ordinals.get("a"), "first sequential step");
        assertEquals(2L, ordinals.get("b"), "second sequential step");
        assertEquals(3L, ordinals.get("c"), "fan-out claims one ordinal");
        assertEquals(3L, ordinals.get("d"), "shared by every genuinely parallel sibling");
    }

    /** Account recording the upstream-failure and success signals the dispatcher forwards. */
    static final class SignalRecordingLimiter extends AbstractRateLimiter<Object> {
        final AtomicInteger failures = new AtomicInteger();
        final AtomicInteger successes = new AtomicInteger();
        volatile UpstreamFailure lastFailure;

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
            return "signal-recording";
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
            successes.incrementAndGet();
        }

        @Override
        public void onRateLimitError(UpstreamFailure failure) {
            failures.incrementAndGet();
            lastFailure = failure;
        }

        @Override
        public RateLimiterStatus getStatus() {
            return () -> "signal-recording";
        }
    }

    /** A unit gate whose take and give instants are recorded, to watch what a pacing job holds. */
    static final class WatchedGate extends CountingGate {
        final List<Long> takes = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<Long> gives = new java.util.concurrent.CopyOnWriteArrayList<>();

        WatchedGate() {
            super(4);
        }

        @Override
        public boolean tryTake(List<Void> mine, List<Void> reservedAhead) {
            boolean taken = super.tryTake(mine, reservedAhead);
            if (taken) {
                takes.add(System.nanoTime());
            }
            return taken;
        }

        @Override
        public void give(List<Void> amounts) {
            super.give(amounts);
            gives.add(System.nanoTime());
        }

        @Override
        public String limiterName() {
            return "watched";
        }
    }

    /** Fails its first attempt with an upstream retry signal, so the dispatcher paces and re-admits. */
    static class ThrottledOnceJob extends AbstractJob<String> {
        private final WatchedGate gate;
        final AtomicInteger attempts = new AtomicInteger();
        final CountDownLatch threw = new CountDownLatch(1);

        ThrottledOnceJob(Identifiable parent, WatchedGate gate) {
            super(parent, "throttled-once-fixture");
            this.gate = gate;
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = new JobRequirements();
            req.requireRateLimiter(gate, null);
            return req;
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            if (attempts.incrementAndGet() == 1) {
                threw.countDown();
                throw new ai.redouble.nucleo.harness.errors.retry.TransientErrorRetryException("fixture upstream fault", "fixture", "boom", 500, 0, null);
            }
            return "ok";
        }
    }

    /**
     * A provider whose cooperative close blocks until aborted: the shape of a stuck close that
     * only the timeout's aggressive phase can cut.
     */
    static final class StuckCloseProvider implements DBResourceProvider<Object> {
        private final DatabaseGate gate = new DatabaseGate("stuck-fixture", 1);
        final CountDownLatch aborted = new CountDownLatch(1);

        @Override
        public String name() {
            return "stuck-fixture";
        }

        @Override
        public RateLimiter<Void> admission() {
            return gate;
        }

        @Override
        public DBManagedResource<Object> acquire(boolean readOnly) {
            return () -> "handle";
        }

        @Override
        public void begin(DBManagedResource<Object> resource) {
        }

        @Override
        public void commit(DBManagedResource<Object> resource) {
        }

        @Override
        public void awaitCompletion(DBManagedResource<Object> resource) {
        }

        @Override
        public void rollback(DBManagedResource<Object> resource) {
        }

        @Override
        public void close(DBManagedResource<Object> resource) {
            try {
                aborted.await(10, TimeUnit.SECONDS);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void abort(DBManagedResource<Object> resource) {
            aborted.countDown();
        }
    }

    /** Ignores its one-second timeout for three seconds, holding a handle whose close is stuck. */
    static class StubbornJob extends AbstractJob<String> {
        private final StuckCloseProvider provider;

        StubbornJob(Identifiable parent, StuckCloseProvider provider) {
            super(parent, "stubborn-fixture");
            this.provider = provider;
            setTimeout(Duration.ofSeconds(1));
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = new JobRequirements();
            req.addProvider(provider);
            return req;
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) throws Exception {
            Thread.sleep(3_000);
            return "too late";
        }
    }

    static class LimitedJob extends AbstractJob<String> {
        private final SignalRecordingLimiter limiter;
        private final boolean fail;

        LimitedJob(Identifiable parent, SignalRecordingLimiter limiter, boolean fail) {
            super(parent, "limited-fixture");
            this.limiter = limiter;
            this.fail = fail;
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = new JobRequirements();
            req.requireRateLimiter(limiter, null);
            return req;
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) throws Exception {
            if (fail) {
                throw new ExternalServiceException("fixture-service", "upstream said no");
            }
            return "ok";
        }
    }

    @Test
    void externalServiceFailureFeedsTheJobsLimiters_successFeedsRecovery() throws Exception {
        SignalRecordingLimiter limiter = new SignalRecordingLimiter();
        assertThrows(ExecutionException.class,
                () -> JobDispatcher.getInstance().submit(new LimitedJob(root(), limiter, true)).get());
        assertEquals(1, limiter.failures.get(),
                "an ExternalServiceException reaches the declared limiter as an UpstreamFailure - the bridge that lets circuits react to tool-level failures");
        assertNotNull(limiter.lastFailure);

        assertEquals("ok", JobDispatcher.getInstance().submit(new LimitedJob(root(), limiter, false)).get());
        assertEquals(1, limiter.successes.get(),
                "success feeds throttle recovery on the same limiters");
    }

    @Test
    void aPacingJobHoldsNothingWhileItWaits() throws Exception {
        // The transparent retry closes the attempt's resources before it sleeps the pacing
        // delay, so the permit set is free for the whole wait. The re-admission that follows
        // the delay is not observed here: every upstream signal's jitter floor is five seconds.
        WatchedGate gate = new WatchedGate();
        ThrottledOnceJob job = new ThrottledOnceJob(root(), gate);
        JobHandle<String> handle = JobDispatcher.getInstance().submit(job);
        assertTrue(job.threw.await(10, TimeUnit.SECONDS), "the first attempt threw the retry signal");

        assertTrue(AdmissionFixtures.await(() -> gate.currentInUse() == 0, 2_000),
                "within the pacing delay the permit is already back: the job paces holding nothing");
        assertEquals(1, gate.takes.size());
        assertEquals(1, gate.gives.size());

        handle.cancel("test done");
    }

    /** A job whose upstream faults on every attempt, with the retry budget its constructor was given. */
    static final class FaultingJob extends AbstractJob<String> {
        final AtomicInteger attempts = new AtomicInteger();

        FaultingJob(Identifiable parent, int upstreamRetries) {
            super(parent, "faulting-fixture");
            setUpstreamRetries(upstreamRetries);
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = new JobRequirements();
            req.setRequiresTransaction(false);
            return req;
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            attempts.incrementAndGet();
            throw new ai.redouble.nucleo.harness.errors.retry.TransientErrorRetryException("fixture upstream fault", "fixture", "boom", 500, 0, null);
        }
    }

    @Test
    void theUpstreamRetryBudgetIsTheJobsOwn() throws Exception {
        assertEquals(3, Job.DEFAULT_UPSTREAM_RETRIES, "three re-runs by default: one clears a blip, a fault still there on the third is an outage");
        assertEquals(Job.DEFAULT_UPSTREAM_RETRIES, new ThrottledOnceJob(root(), new WatchedGate()).getUpstreamRetries(),
                "a job that sets nothing carries the default");
        // Zero: the wiring is observable without sleeping a single pacing delay, and it is a
        // legitimate choice for a caller that wants the first signal reported as the failure
        FaultingJob job = new FaultingJob(root(), 0);
        ExecutionException failure = assertThrows(ExecutionException.class, () -> JobDispatcher.getInstance().submit(job).get(10, TimeUnit.SECONDS));
        assertEquals(1, job.attempts.get(), "no re-run was granted past the budget");
        assertInstanceOf(UncorrectableRuntimeLLMException.class, failure.getCause(), "past the budget the job fails as uncorrectable: " + failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("transparent retry budget (0)"), "the failure names the budget: " + failure.getCause().getMessage());
        assertTrue(failure.getCause().getMessage().contains("fixture upstream fault"), "and the last signal's own words: " + failure.getCause().getMessage());
        assertInstanceOf(ai.redouble.nucleo.harness.errors.retry.TransientErrorRetryException.class, failure.getCause().getCause(), "with the last signal as cause");
    }

    @Test
    void aStuckCooperativeClose_isCutByTheTimeoutsAggressivePhase() throws Exception {
        StuckCloseProvider provider = new StuckCloseProvider();
        JobHandle<String> handle = JobDispatcher.getInstance().submit(new StubbornJob(root(), provider));

        assertThrows(ExecutionException.class, () -> handle.get(20, TimeUnit.SECONDS), "the job ends by timeout, not by finishing");
        assertTrue(provider.aborted.await(5, TimeUnit.SECONDS), "phase 2 reached forceClose and aborted the stuck handle");
        assertTrue(AdmissionFixtures.await(() -> provider.gate.currentInUse() == 0, 5_000), "the gate permit came back exactly once");
    }
}
