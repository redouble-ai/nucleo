/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.http.*;
import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the resource half of the runtime's contract - the promises Nucleo's PACKAGE.md
 * leads with:
 *
 * <ul>
 *   <li><b>No Waiting While Holding</b>: any job holding any resource that calls
 *       {@code handle.get()} or {@code handle.get(timeout, unit)} on any other job receives
 *       {@link JobDeadlockException} naming both jobs and the job's own call site -
 *       unconditionally, even when the awaited job has already completed, because the
 *       rule is structural, not a liveness heuristic. Orchestrators, which hold nothing,
 *       block on children freely.</li>
 *   <li><b>Whole-demand admission with atomic rollback</b>: when materialization fails after
 *       the grant, everything the grant took is returned exactly once and the job never
 *       executes - no leaked permits.</li>
 *   <li><b>RELEASE-only close</b>: on completion, permits go back only to accounts whose
 *       {@code replenishment()} is RELEASE; a TIME account's spent window permit is left
 *       to refill with time (returning it would defeat throttling).</li>
 *   <li><b>A resource-holding job declares a timeout</b>: the door refuses one that does not,
 *       because every held permit must return in finite time.</li>
 *   <li><b>Force-close never races the job thread</b>: the handle container is fixed at
 *       construction, so a force-close during {@code commitAll} cannot throw.</li>
 * </ul>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-31)
 */
class ResourceContractTest {

    @BeforeAll
    static void startDispatcher() {
        JobDispatcher.getInstance().start();
    }

    private static Identifiable root() {
        return Job.workflow("resource-contract-test", "resource-contract-test");
    }

    /** Always-fitting account that records every take and give it receives. */
    static final class RecordingLimiter extends AbstractRateLimiter<Object> {
        final Replenishment kind;
        final AtomicInteger takes = new AtomicInteger();
        final AtomicInteger gives = new AtomicInteger();

        RecordingLimiter(Replenishment kind) {
            this.kind = kind;
        }

        @Override
        public Replenishment replenishment() {
            return kind;
        }

        @Override
        public boolean fits(List<Object> mine, List<Object> reservedAhead) {
            return true;
        }

        @Override
        public boolean tryTake(List<Object> mine, List<Object> reservedAhead) {
            takes.incrementAndGet();
            return true;
        }

        @Override
        public void give(List<Object> amounts) {
            gives.incrementAndGet();
        }

        @Override
        public Long earliestFit(List<Object> mine, List<Object> reservedAhead) {
            return null;
        }

        @Override
        public String limiterName() {
            return "recording-" + kind;
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
            return takes.get() - gives.get();
        }

        @Override
        public void onSuccess() {
        }

        @Override
        public void onRateLimitError(UpstreamFailure failure) {
        }

        @Override
        public RateLimiterStatus getStatus() {
            return () -> "recording";
        }
    }

    /** A database provider whose materialization always fails - the "later step" of the rollback contract. */
    static final class RefusingProvider implements DBResourceProvider<Object> {
        private final DatabaseGate gate = new DatabaseGate("refusing-fixture", 1);

        @Override
        public String name() {
            return "refusing-fixture";
        }

        @Override
        public RateLimiter<Void> admission() {
            return gate;
        }

        @Override
        public DBManagedResource<Object> acquire(boolean readOnly) {
            throw new IllegalStateException("fixture provider refuses acquisition");
        }

        @Override
        public void begin(DBManagedResource<Object> resource) {
            throw new UnsupportedOperationException("unreachable: acquire always refuses");
        }

        @Override
        public void commit(DBManagedResource<Object> resource) {
            throw new UnsupportedOperationException("unreachable: acquire always refuses");
        }

        @Override
        public void awaitCompletion(DBManagedResource<Object> resource) {
            throw new UnsupportedOperationException("unreachable: acquire always refuses");
        }

        @Override
        public void rollback(DBManagedResource<Object> resource) {
            throw new UnsupportedOperationException("unreachable: acquire always refuses");
        }

        @Override
        public void close(DBManagedResource<Object> resource) {
            throw new UnsupportedOperationException("unreachable: acquire always refuses");
        }

        @Override
        public void abort(DBManagedResource<Object> resource) {
            throw new UnsupportedOperationException("unreachable: acquire always refuses");
        }
    }

    /**
     * A database provider whose commit blocks until it is aborted - the shape of a cooperative
     * close that never finishes, which phase 2 of the timeout must be able to cut.
     */
    static final class BlockingCommitProvider implements DBResourceProvider<Object> {
        private final DatabaseGate gate = new DatabaseGate("blocking-fixture", 1);
        final CountDownLatch committing = new CountDownLatch(1);
        final CountDownLatch released = new CountDownLatch(1);
        final AtomicBoolean aborted = new AtomicBoolean(false);
        final AtomicBoolean closed = new AtomicBoolean(false);

        @Override
        public String name() {
            return "blocking-fixture";
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
            committing.countDown();
            try {
                released.await(10, TimeUnit.SECONDS);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void awaitCompletion(DBManagedResource<Object> resource) {
        }

        @Override
        public void rollback(DBManagedResource<Object> resource) {
        }

        @Override
        public void close(DBManagedResource<Object> resource) {
            closed.set(true);
        }

        @Override
        public void abort(DBManagedResource<Object> resource) {
            aborted.set(true);
            released.countDown();
        }
    }

    /** Resource-free job that completes immediately. */
    static class QuickJob extends AbstractJob<String> {
        QuickJob(Identifiable parent) {
            super(parent, "quick-fixture");
        }

        @Override
        public JobRequirements getRequirements() {
            return new JobRequirements();
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            return "done";
        }
    }

    /** Resource-holding job whose only act is the forbidden one: awaiting a sibling. */
    static class HoldingGetJob extends AbstractJob<String> {
        private final JobHandle<String> sibling;
        private final RecordingLimiter limiter;

        HoldingGetJob(Identifiable parent, JobHandle<String> sibling, RecordingLimiter limiter) {
            super(parent, "holding-get-fixture");
            this.sibling = sibling;
            this.limiter = limiter;
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = new JobRequirements();
            req.requireRateLimiter(limiter, null);
            return req;
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) throws Exception {
            return sibling.get();
        }
    }

    /** Two recording accounts of opposite kinds, held through a successful run. */
    static class TwoLimiterJob extends AbstractJob<String> {
        private final RecordingLimiter releaseKind;
        private final RecordingLimiter timeKind;

        TwoLimiterJob(Identifiable parent, RecordingLimiter releaseKind, RecordingLimiter timeKind) {
            super(parent, "two-limiter-fixture");
            this.releaseKind = releaseKind;
            this.timeKind = timeKind;
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = new JobRequirements();
            req.requireRateLimiter(releaseKind, null);
            req.requireRateLimiter(timeKind, null);
            return req;
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            return "ok";
        }
    }

    /** Limiter-then-database job whose database half refuses to materialize, forcing the rollback. */
    static class RollbackVictimJob extends AbstractJob<String> {
        private final RecordingLimiter limiter;
        final AtomicBoolean executed = new AtomicBoolean(false);

        RollbackVictimJob(Identifiable parent, RecordingLimiter limiter) {
            super(parent, "rollback-victim-fixture");
            this.limiter = limiter;
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = new JobRequirements();
            req.requireRateLimiter(limiter, null);
            req.addProvider(new RefusingProvider());
            return req;
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            executed.set(true);
            return "never";
        }
    }

    /** A resource-holding job that declares no timeout: the door must refuse it. */
    static class NoTimeoutJob extends AbstractJob<String> {
        private final RecordingLimiter limiter;

        NoTimeoutJob(Identifiable parent, RecordingLimiter limiter) {
            super(parent, "no-timeout-fixture");
            this.limiter = limiter;
            setTimeout(null);
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = new JobRequirements();
            req.requireRateLimiter(limiter, null);
            return req;
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            return "never";
        }
    }

    /** A job that declares the blocking provider, for the direct JobResources test. */
    static class CommitterJob extends AbstractJob<String> {
        private final BlockingCommitProvider provider;

        CommitterJob(Identifiable parent, BlockingCommitProvider provider) {
            super(parent, "committer-fixture");
            this.provider = provider;
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = new JobRequirements();
            req.addProvider(provider);
            return req;
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            return "unused";
        }
    }

    /** Orchestrator control: holds nothing, so blocking on a child is legal. */
    static class WaitingDoer extends ai.redouble.nucleo.tools.AbstractDoer<String, String> {
        WaitingDoer(Identifiable parent) {
            super(parent, "waiting-doer-fixture");
        }

        @Override
        public String execute(JobContext<String> context) throws LLMReadableCheckedException {
            try {
                return submitInStep(new QuickJob(this)).get();
            }
            catch (Exception e) {
                throw LLMReadableCheckedException.unwrap(e);
            }
        }
    }

    private static boolean chainContains(Throwable t, Class<? extends Throwable> type) {
        for (Throwable current = t; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    /** The same forbidden act through the timed get. */
    static class TimedHoldingGetJob extends AbstractJob<String> {
        private final JobHandle<String> sibling;
        private final RecordingLimiter limiter;

        TimedHoldingGetJob(Identifiable parent, JobHandle<String> sibling, RecordingLimiter limiter) {
            super(parent, "timed-holding-get-fixture");
            this.sibling = sibling;
            this.limiter = limiter;
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = new JobRequirements();
            req.requireRateLimiter(limiter, null);
            return req;
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) throws Exception {
            return sibling.get(1, TimeUnit.SECONDS);
        }
    }

    private static JobDeadlockException deadlockIn(Throwable t) {
        for (Throwable current = t; current != null; current = current.getCause()) {
            if (current instanceof JobDeadlockException deadlock) {
                return deadlock;
            }
        }
        return null;
    }

    @Test
    void resourceHoldingGetDeadlocks_evenWhenTheSiblingAlreadyCompleted() throws Exception {
        JobHandle<String> sibling = JobDispatcher.getInstance().submit(new QuickJob(root()));
        assertEquals("done", sibling.get(), "sibling must be complete before the rule is probed");
        HoldingGetJob holder = new HoldingGetJob(root(), sibling, new RecordingLimiter(RateLimiter.Replenishment.RELEASE));
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> JobDispatcher.getInstance().submit(holder).get());
        JobDeadlockException deadlock = deadlockIn(failure);
        assertNotNull(deadlock,
                "the rule is structural: a completed sibling would return instantly, and the check must refuse anyway: " + failure);
        assertTrue(deadlock.getCallLocation().contains("HoldingGetJob"),
                "the refusal names the job's own call site, not the handle's: " + deadlock.getCallLocation());
        assertEquals(holder.getId(), deadlock.getCallerJobId());
        assertEquals(sibling.getJobId(), deadlock.getCalledJobId());
    }

    @Test
    void theTimedGetIsRefusedWhileHoldingResourcesToo() throws Exception {
        JobHandle<String> sibling = JobDispatcher.getInstance().submit(new QuickJob(root()));
        assertEquals("done", sibling.get());
        TimedHoldingGetJob holder = new TimedHoldingGetJob(root(), sibling, new RecordingLimiter(RateLimiter.Replenishment.RELEASE));
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> JobDispatcher.getInstance().submit(holder).get());
        assertNotNull(deadlockIn(failure), "a bounded wait is still a wait while holding: " + failure);
    }

    @Test
    void orchestratorGetRemainsLegal() throws Exception {
        WaitingDoer doer = new WaitingDoer(root());
        doer.setInput("x");
        assertEquals("done", JobDispatcher.getInstance().submit(doer).get(),
                "a resource-free coordinator blocks on children freely");
    }

    @Test
    void materializationFailureRollsBackTheGrant_andTheJobNeverRuns() {
        RecordingLimiter limiter = new RecordingLimiter(RateLimiter.Replenishment.RELEASE);
        RollbackVictimJob victim = new RollbackVictimJob(root(), limiter);
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> JobDispatcher.getInstance().submit(victim).get());
        assertEquals(1, limiter.takes.get(), "the account was taken as part of the grant before the database refused");
        assertEquals(1, limiter.gives.get(), "rollback returns the permit exactly once - no leak, no double refund");
        assertFalse(victim.executed.get(), "a job whose materialization failed must never execute");
        assertTrue(String.valueOf(failure).contains("refus"),
                "the failure surfaces the acquisition cause: " + failure);
    }

    @Test
    void closeReturnsPermitsOnlyToReleaseAccounts() throws Exception {
        RecordingLimiter releaseKind = new RecordingLimiter(RateLimiter.Replenishment.RELEASE);
        RecordingLimiter timeKind = new RecordingLimiter(RateLimiter.Replenishment.TIME);
        assertEquals("ok", JobDispatcher.getInstance().submit(new TwoLimiterJob(root(), releaseKind, timeKind)).get());
        assertEquals(1, releaseKind.takes.get());
        assertEquals(1, releaseKind.gives.get(), "a held slot is free once the job ends");
        assertEquals(1, timeKind.takes.get());
        assertEquals(0, timeKind.gives.get(),
                "a TIME permit is spent against the window; returning it would make throughput a function of job duration");
    }

    @Test
    void aResourceHoldingJobWithoutATimeout_isRefusedAtTheDoor() {
        RecordingLimiter limiter = new RecordingLimiter(RateLimiter.Replenishment.RELEASE);
        JobHandle<String> handle = JobDispatcher.getInstance().submit(new NoTimeoutJob(root(), limiter));
        ExecutionException failure = assertThrows(ExecutionException.class, handle::get);
        assertTrue(chainContains(failure, SystemException.class),
                "the refusal is the door's own type, delivered as a born-failed handle: " + failure);
        assertTrue(String.valueOf(failure).contains("timeout"), failure.toString());
        assertEquals(0, limiter.takes.get(), "a refused job never reaches admission");
    }

    @Test
    void forceCloseDuringCommitAll_doesNotRaceTheJobThread() throws Exception {
        BlockingCommitProvider provider = new BlockingCommitProvider();
        Admission admission = new Admission(MemoryPressureGate.getInstance());
        admission.start();
        try {
            CommitterJob job = new CommitterJob(root(), provider);
            JobRequirements requirements = job.getRequirements();
            JobContext<String> context = new JobContext<>(job, "tester", Duration.ofMinutes(1), requirements);
            JobResources resources = new JobResources(requirements, context, admission);
            resources.beginAll();
            AtomicReference<Throwable> commitOutcome = new AtomicReference<>();
            Thread committer = Thread.ofVirtual().start(() -> {
                try {
                    resources.commitAll();
                }
                catch (Throwable t) {
                    commitOutcome.set(t);
                }
            });
            assertTrue(provider.committing.await(5, TimeUnit.SECONDS), "the job thread is inside commitAll");

            resources.forceClose();

            committer.join(5_000);
            assertFalse(committer.isAlive(), "the abort unblocked the commit");
            assertTrue(provider.aborted.get(), "force-close aborted the handle");
            assertTrue(provider.closed.get(), "and closed it");
            assertNull(commitOutcome.get(), "commitAll iterated a container that never changed: no ConcurrentModificationException: " + commitOutcome.get());
            assertEquals(0, provider.gate.currentInUse(), "the grant released the gate permit exactly once");
            resources.close();
            assertEquals(0, provider.gate.currentInUse(), "a later close is a no-op on the settled grant");
        }
        finally {
            admission.stop();
        }
    }
}
