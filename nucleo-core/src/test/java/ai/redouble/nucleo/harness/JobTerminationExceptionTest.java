/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import ai.redouble.nucleo.harness.errors.*;
import org.junit.jupiter.api.*;

import java.time.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the dispatcher's terminal translation of a job it itself terminated:
 * a timed-out job's in-flight casualty becomes {@link JobTimeoutException}, and
 * an in-flight cooperative cancel becomes {@link JobCancelledException}, instead
 * of the raw downstream exception leaking to the caller / LLM.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-14)
 */
public class JobTerminationExceptionTest {
    private static final Identifiable TEST_ROOT = Job.workflow("test-user", "termination-test");

    @BeforeAll
    static void boot() {
        JobDispatcher.getInstance().start();
    }

    /** Walks the cause chain looking for an instance of the given type. */
    private static <X extends Throwable> X findCause(Throwable t, Class<X> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return type.cast(c);
            }
        }
        return null;
    }

    /** Sleeps far past its tiny timeout; on the Phase-2 interrupt it throws a raw
     * exception standing in for the in-flight DB statement failing on a reclaimed session. */
    private static class SlowDbJob extends AbstractJob<String> {
        SlowDbJob() {
            super(TEST_ROOT, "slow-db-job");
        }

        @Override
        public Duration getTimeout() {
            return Duration.ofMillis(200);
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = new JobRequirements();
            req.setRequiresTransaction(false);
            return req;
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            try {
                Thread.sleep(5000);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Statement closed");
            }
            return "should not reach";
        }
    }

    /** Loops on checkCancellation() so an external cancel surfaces as the nested
     * JobContext.CancellationException from inside a running job. */
    private static class CooperativeCancelJob extends AbstractJob<String> {
        final CountDownLatch started = new CountDownLatch(1);

        CooperativeCancelJob() {
            super(TEST_ROOT, "cooperative-cancel-job");
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = new JobRequirements();
            req.setRequiresTransaction(false);
            return req;
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) throws Exception {
            started.countDown();
            while (true) {
                context.checkCancellation();
                Thread.sleep(50);
            }
        }
    }

    @Test
    void timeoutCasualtyBecomesJobTimeoutException() {
        SlowDbJob job = new SlowDbJob();
        JobHandle<String> handle = JobDispatcher.getInstance().submit(job);
        ExecutionException ex = assertThrows(ExecutionException.class, handle::get);
        JobTimeoutException timeout = findCause(ex, JobTimeoutException.class);
        assertNotNull(timeout, "expected a JobTimeoutException in the cause chain, got " + ex.getCause());
        assertFalse(timeout.isCorrectable());
        assertNotNull(timeout.getCause(), "the raw casualty should be preserved as the cause");
        assertEquals(JobState.TIMED_OUT, handle.getContext().getState());
    }

    @Test
    void cancelCasualtyBecomesJobCancelledException() throws Exception {
        CooperativeCancelJob job = new CooperativeCancelJob();
        JobHandle<String> handle = JobDispatcher.getInstance().submit(job);
        assertTrue(job.started.await(2, TimeUnit.SECONDS), "job did not start");
        JobDispatcher.getInstance().cancel(job.getId(), "test cancel");
        ExecutionException ex = assertThrows(ExecutionException.class, handle::get);
        JobCancelledException cancelled = findCause(ex, JobCancelledException.class);
        assertNotNull(cancelled, "expected a JobCancelledException in the cause chain, got " + ex.getCause());
        assertFalse(cancelled.isCorrectable());
        assertInstanceOf(JobContext.CancellationException.class, cancelled.getCause());
        assertEquals(JobState.CANCELLED, handle.getContext().getState());
    }

    @Test
    void exceptionsAreUncorrectableAndExplainToLLM() {
        JobTimeoutException t = new JobTimeoutException(Duration.ofMinutes(30), new RuntimeException("Statement closed"));
        assertFalse(t.isCorrectable());
        assertTrue(t.getLLMMessage().contains("time budget"));
        assertTrue(t.explainToLLM().contains("not correctable"));

        JobCancelledException c = new JobCancelledException(new RuntimeException("boom"));
        assertFalse(c.isCorrectable());
        assertTrue(c.getLLMMessage().contains("cancelled"));
        assertTrue(c.explainToLLM().contains("not correctable"));
    }
}
