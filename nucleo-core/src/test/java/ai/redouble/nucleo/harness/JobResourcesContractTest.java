/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.http.*;
import ai.redouble.nucleo.http.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link JobResources} on its own: the demand it assembles from a job's requirements (the
 * HTTP gate when the job makes HTTP calls, each custom limiter with its amount, each declared
 * provider's gate, nothing for null requirements); a provider the job did not declare is
 * refused; {@code commitAll} commits in declaration order and rolls back the uncommitted
 * remainder on a failure, keeping what committed, with a rollback that fails as suppressed; a
 * demand that does not fit as a whole parks holding nothing; {@code close} closes every handle
 * before the grant returns and aggregates close failures; an interrupt while parked in
 * admission is an uncorrectable failure with the interrupt restored.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class JobResourcesContractTest {
    private static final Identifiable ROOT = Job.workflow("resources-user", "resources-contract-test");

    private Admission admission;

    @BeforeEach
    void startAdmission() {
        admission = new Admission(MemoryPressureGate.getInstance());
        admission.start();
    }

    @AfterEach
    void stopAdmission() {
        admission.stop();
    }

    /** A provider whose every call is recorded and whose commit, rollback or close can be made to fail. */
    static final class ScriptedProvider implements DBResourceProvider<Object> {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final DatabaseGate gate;
        final boolean failCommit;
        final boolean failClose;
        boolean failRollback;

        ScriptedProvider(String name, boolean failCommit, boolean failClose) {
            this.gate = new DatabaseGate(name, 2);
            this.failCommit = failCommit;
            this.failClose = failClose;
        }

        @Override
        public String name() {
            return gate.limiterName();
        }

        @Override
        public RateLimiter<Void> admission() {
            return gate;
        }

        @Override
        public DBManagedResource<Object> acquire(boolean readOnly) {
            calls.add("acquire");
            return () -> name() + "-handle";
        }

        @Override
        public void begin(DBManagedResource<Object> resource) {
            calls.add("begin");
        }

        @Override
        public void commit(DBManagedResource<Object> resource) {
            calls.add("commit");
            if (failCommit) {
                throw new IllegalStateException(name() + " commit failed");
            }
        }

        @Override
        public void awaitCompletion(DBManagedResource<Object> resource) {
            calls.add("await");
        }

        @Override
        public void rollback(DBManagedResource<Object> resource) {
            calls.add("rollback");
            if (failRollback) {
                throw new IllegalStateException(name() + " rollback failed");
            }
        }

        @Override
        public void close(DBManagedResource<Object> resource) {
            calls.add("close");
            if (failClose) {
                throw new IllegalStateException(name() + " close failed");
            }
        }

        @Override
        public void abort(DBManagedResource<Object> resource) {
            calls.add("abort");
        }
    }

    /** An account that never fits and names no instant: a waiter parks on it until an event. */
    static final class NeverFits extends AbstractRateLimiter<Object> {
        @Override
        public boolean fits(List<Object> mine, List<Object> reservedAhead) {
            return false;
        }

        @Override
        public boolean tryTake(List<Object> mine, List<Object> reservedAhead) {
            return false;
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
            return "never-fits";
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
            return 1;
        }

        @Override
        public void onSuccess() {
        }

        @Override
        public void onRateLimitError(UpstreamFailure failure) {
        }

        @Override
        public RateLimiterStatus getStatus() {
            return () -> "never-fits";
        }
    }

    /** A gate of one that always has room, to read whether a parked demand took it. */
    static final class OneGate extends CountingGate {
        OneGate() {
            super(1);
        }

        @Override
        public String limiterName() {
            return "one-gate";
        }
    }

    static class HolderJob extends AbstractJob<String> {
        private final JobRequirements requirements;

        HolderJob(JobRequirements requirements) {
            super(ROOT, "holder");
            this.requirements = requirements;
        }

        @Override
        public JobRequirements getRequirements() {
            return requirements;
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            return "unused";
        }
    }

    private JobResources resourcesFor(JobRequirements requirements) {
        HolderJob job = new HolderJob(requirements);
        JobContext<String> context = new JobContext<>(job, "resources-user", Duration.ofMinutes(1), requirements);
        return new JobResources(requirements, context, admission);
    }

    private static boolean names(Demand demand, RateLimiter<?> limiter) {
        return demand.getEntries().stream().anyMatch(entry -> entry.getLimiter() == limiter);
    }

    @Test
    void theDemandIsAssembledFromTheRequirements() {
        assertTrue(JobResources.demandOf(null).isEmpty(), "null requirements (an orchestrator) yield the empty demand");
        assertTrue(JobResources.demandOf(new JobRequirements()).isEmpty(), "nothing declared, nothing demanded");
        JobRequirements http = new JobRequirements();
        http.setRequiresHttpConnection(true);
        assertTrue(names(JobResources.demandOf(http), HttpConnectionPools.getInstance().gate()), "a job that makes HTTP calls demands the shared HTTP gate");
        NeverFits custom = new NeverFits();
        ScriptedProvider provider = new ScriptedProvider("demand-fixture", false, false);
        JobRequirements full = new JobRequirements();
        full.requireRateLimiter(custom, 3);
        full.addProvider(provider);
        Demand demand = JobResources.demandOf(full);
        assertTrue(names(demand, custom), "each custom limiter is an entry");
        assertEquals(List.of(3), demand.getEntries().stream().filter(e -> e.getLimiter() == custom).findFirst().orElseThrow().getAmounts(),
                "with the amount it was declared with");
        assertTrue(names(demand, provider.admission()), "each declared provider's gate is an entry");
        assertFalse(names(demand, HttpConnectionPools.getInstance().gate()), "no HTTP gate for a job that declared none");
    }

    @Test
    void anUndeclaredProviderIsRefused() throws Exception {
        ScriptedProvider declared = new ScriptedProvider("declared-fixture", false, false);
        ScriptedProvider stranger = new ScriptedProvider("stranger-fixture", false, false);
        JobRequirements requirements = new JobRequirements();
        requirements.addProvider(declared);
        JobResources resources = resourcesFor(requirements);
        try {
            assertEquals(declared.name() + "-handle", resources.get(declared), "a declared provider's handle unwraps");
            assertNotNull(resources.getHandle(declared));
            assertTrue(resources.hasDatabase());
            IllegalStateException refusal = assertThrows(IllegalStateException.class, () -> resources.get(stranger));
            assertTrue(refusal.getMessage().contains("not declared"), refusal.getMessage());
            assertThrows(IllegalStateException.class, () -> resources.getHandle(stranger));
        }
        finally {
            resources.close();
        }
    }

    @Test
    void commitAllCommitsInOrder_andAFailureRollsBackOnlyTheUncommittedRemainder() throws Exception {
        ScriptedProvider first = new ScriptedProvider("first-fixture", false, false);
        ScriptedProvider second = new ScriptedProvider("second-fixture", true, false);
        ScriptedProvider third = new ScriptedProvider("third-fixture", false, false);
        JobRequirements requirements = new JobRequirements();
        requirements.addProvider(first);
        requirements.addProvider(second);
        requirements.addProvider(third);
        JobResources resources = resourcesFor(requirements);
        try {
            resources.beginAll();
            UncorrectableRuntimeLLMException failure = assertThrows(UncorrectableRuntimeLLMException.class, resources::commitAll);
            assertTrue(failure.getMessage().contains("second-fixture"), "the failure names the provider that failed: " + failure.getMessage());
            assertInstanceOf(IllegalStateException.class, failure.getCause(), "with the provider's own failure as cause");
            assertEquals(List.of("acquire", "begin", "commit", "await"), first.calls, "the first committed and stays committed");
            assertEquals(List.of("acquire", "begin", "commit", "rollback"), second.calls, "the failing provider is rolled back as best effort");
            assertEquals(List.of("acquire", "begin", "rollback"), third.calls, "the remainder is rolled back, never committed");
            resources.rollbackAll();
            assertEquals(List.of("acquire", "begin", "commit", "await"), first.calls, "rollbackAll skips what committed");
            assertEquals(List.of("acquire", "begin", "rollback", "rollback"), third.calls);
        }
        finally {
            resources.close();
        }
    }

    @Test
    void aRollbackThatFailsDuringCommitAllRidesAsSuppressed() throws Exception {
        ScriptedProvider first = new ScriptedProvider("first-fixture", true, false);
        ScriptedProvider second = new ScriptedProvider("second-fixture", false, false);
        second.failRollback = true;
        JobRequirements requirements = new JobRequirements();
        requirements.addProvider(first);
        requirements.addProvider(second);
        JobResources resources = resourcesFor(requirements);
        try {
            resources.beginAll();
            UncorrectableRuntimeLLMException failure = assertThrows(UncorrectableRuntimeLLMException.class, resources::commitAll);
            assertInstanceOf(IllegalStateException.class, failure.getCause(), "the commit failure is the cause");
            assertTrue(failure.getCause().getMessage().contains("commit failed"), failure.getCause().getMessage());
            assertEquals(List.of("acquire", "begin", "rollback"), second.calls, "the uncommitted remainder was rolled back");
            assertEquals(1, failure.getSuppressed().length, "the rollback that failed rides as suppressed, never as the story");
            assertTrue(failure.getSuppressed()[0].getMessage().contains("second-fixture rollback failed"), failure.getSuppressed()[0].getMessage());
        }
        finally {
            resources.close();
        }
    }

    @Test
    void aDemandThatDoesNotFitAsAWholeParksHoldingNothing() throws Exception {
        OneGate open = new OneGate();
        JobRequirements requirements = new JobRequirements();
        requirements.requireRateLimiter(open, null);
        requirements.requireRateLimiter(new NeverFits(), null);
        CountDownLatch done = new CountDownLatch(1);
        Thread waiter = Thread.ofVirtual().start(() -> {
            try {
                resourcesFor(requirements);
            }
            catch (UncorrectableRuntimeLLMException interrupted) {
                done.countDown();
            }
        });
        assertTrue(AdmissionFixtures.await(() -> admission.queueSize() == 1, 2_000), "the waiter is parked: one account of its demand never fits");
        assertEquals(0, open.currentInUse(), "the account that fits is not taken while the demand as a whole does not: the waiter holds nothing");
        waiter.interrupt();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(0, open.currentInUse());
    }

    @Test
    void closeClosesEveryHandleBeforeTheGrantReturns_andAggregatesFailures() throws Exception {
        ScriptedProvider fine = new ScriptedProvider("fine-fixture", false, false);
        ScriptedProvider broken = new ScriptedProvider("broken-fixture", false, true);
        JobRequirements requirements = new JobRequirements();
        requirements.addProvider(broken);
        requirements.addProvider(fine);
        JobResources resources = resourcesFor(requirements);
        assertEquals(1, fine.gate.currentInUse(), "the grant holds each provider's permit");
        IOException failure = assertThrows(IOException.class, resources::close);
        assertTrue(failure.getMessage().contains("Failed to close database handle"), failure.getMessage());
        assertTrue(failure.getCause().getMessage().contains("close failed"), "the provider's own failure is the cause: " + failure.getCause());
        assertEquals(List.of("acquire", "close"), fine.calls, "every handle is closed even when an earlier one failed");
        assertEquals(0, fine.gate.currentInUse(), "the grant is released regardless");
        assertEquals(0, broken.gate.currentInUse());
    }

    @Test
    void anInterruptWhileParkedInAdmissionIsUncorrectable_andRestoresTheInterrupt() throws Exception {
        JobRequirements requirements = new JobRequirements();
        requirements.requireRateLimiter(new NeverFits(), null);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);
        Thread waiter = Thread.ofVirtual().start(() -> {
            try {
                resourcesFor(requirements);
            }
            catch (Throwable t) {
                outcome.set(t);
                interruptRestored.set(Thread.currentThread().isInterrupted());
            }
            done.countDown();
        });
        Thread.sleep(200);
        assertEquals(1, admission.queueSize(), "the waiter is parked on the account that never fits");
        waiter.interrupt();
        assertTrue(done.await(5, TimeUnit.SECONDS), "the interrupt withdrew the waiter");
        assertInstanceOf(UncorrectableRuntimeLLMException.class, outcome.get(), "an interrupted admission is not the model's to correct: " + outcome.get());
        assertInstanceOf(InterruptedException.class, outcome.get().getCause());
        assertTrue(interruptRestored.get(), "the interrupt flag is restored for whoever owns the thread");
        assertEquals(0, admission.queueSize(), "the withdrawn waiter left the queue");
    }
}
