/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors.retry;

import ai.redouble.nucleo.harness.*;
import org.junit.jupiter.api.*;

import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the dispatcher's transparent response-correction retry arm: a job that
 * throws {@link ResponseCorrectionRetryException} is re-run without consuming its
 * regular attempt, and a job that never stops throwing hits the dispatcher's
 * backstop cap instead of looping forever.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-09)
 */
public class ResponseCorrectionRetryTest {
    private static final Identifiable TEST_ROOT = Job.workflow("test-user", "correction-retry-test");

    @BeforeAll
    static void boot() {
        JobDispatcher.getInstance().start();
    }

    /** Throws the correction signal {@code failures} times, then succeeds. */
    private static class CorrectingJob extends AbstractJob<String> {
        private final int failures;
        private final AtomicInteger executions = new AtomicInteger();

        CorrectingJob(int failures) {
            super(TEST_ROOT, "correcting-job");
            this.failures = failures;
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = new JobRequirements();
            req.setRequiresTransaction(false);
            return req;
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            int run = executions.incrementAndGet();
            if (run <= failures) {
                throw new ResponseCorrectionRetryException("test-model", run,
                        new JsonParseException("malformed on run " + run, null));
            }
            return "ok after " + (run - 1) + " correction(s)";
        }

        int executions() {
            return executions.get();
        }
    }

    @Test
    void retriesTransparentlyAndSucceeds() throws Exception {
        CorrectingJob job = new CorrectingJob(2);
        String result = JobDispatcher.getInstance().submit(job).get();
        assertEquals("ok after 2 correction(s)", result);
        assertEquals(3, job.executions());
    }

    @Test
    void backstopCapStopsRunawayJobs() {
        CorrectingJob job = new CorrectingJob(Integer.MAX_VALUE);
        Exception thrown = assertThrows(Exception.class, () -> JobDispatcher.getInstance().submit(job).get());
        Throwable cause = thrown;
        boolean foundCap = false;
        while (cause != null) {
            if (cause.getMessage() != null && cause.getMessage().contains("Exceeded max response correction retries")) {
                foundCap = true;
                break;
            }
            cause = cause.getCause();
        }
        assertTrue(foundCap, "runaway correction job must hit the dispatcher cap: " + thrown);
        // Cap of 4 transparent retries = 5 executions total.
        assertEquals(5, job.executions());
    }
}
