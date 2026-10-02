/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import ai.redouble.nucleo.harness.errors.*;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A job's state is written by two threads, its own and whoever cancels it, and the terminal
 * state is whichever of them arrives first. These pin that the race has one winner and no
 * exception: a cancel racing a completion ends in exactly one terminal state with its
 * timestamp, a cancel after any terminal state changes nothing, and a running job that never
 * looks at its token and finishes anyway ends cancelled, through the dispatcher, without the
 * terminal guard firing on the job's own thread.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
class JobStateTransitionTest {
    private static final Identifiable ROOT = Job.workflow("state-user", "state-transition-test");

    @BeforeAll
    static void startDispatcher() {
        JobDispatcher.getInstance().start();
    }

    static class NoOpJob extends AbstractJob<String> {
        NoOpJob() {
            super(ROOT, "state-fixture");
        }

        @Override
        public JobRequirements getRequirements() {
            return new JobRequirements();
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            return "x";
        }
    }

    private static JobContext<String> freshContext() {
        return new JobContext<>(new NoOpJob(), "state-user", null, new JobRequirements());
    }

    @Test
    void cancelRacingCompletion_yieldsOneTerminalStateAndNoException() throws Exception {
        int rounds = 2000;
        int completedWins = 0;
        int cancelledWins = 0;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < rounds; i++) {
                JobContext<String> context = freshContext();
                context.setState(JobState.RUNNING);
                CountDownLatch go = new CountDownLatch(1);
                AtomicReference<Throwable> failure = new AtomicReference<>();
                Runnable complete = () -> {
                    try {
                        go.await();
                        context.complete("done");
                    }
                    catch (Throwable t) {
                        failure.set(t);
                    }
                };
                Runnable cancel = () -> {
                    try {
                        go.await();
                        context.cancel("race");
                    }
                    catch (Throwable t) {
                        failure.set(t);
                    }
                };
                Future<?> a = pool.submit(complete);
                Future<?> b = pool.submit(cancel);
                go.countDown();
                a.get(5, TimeUnit.SECONDS);
                b.get(5, TimeUnit.SECONDS);
                assertNull(failure.get(), "no transition may throw on either thread: " + failure.get());
                JobState state = context.getState();
                assertTrue(state == JobState.COMPLETED || state == JobState.CANCELLED, "one of the two terminals, got " + state);
                assertNotNull(context.getSnapshot().getCompletedAt(), "the winning terminal carries its timestamp");
                if (state == JobState.COMPLETED) {
                    completedWins++;
                }
                else {
                    cancelledWins++;
                }
            }
        }
        finally {
            pool.shutdownNow();
        }
        assertEquals(rounds, completedWins + cancelledWins);
    }

    @Test
    void cancelAfterAnyTerminalStateIsANoOp() {
        for (JobState terminal : List.of(JobState.COMPLETED, JobState.FAILED, JobState.TIMED_OUT)) {
            JobContext<String> context = freshContext();
            context.setState(JobState.RUNNING);
            context.setState(terminal);
            assertDoesNotThrow(() -> context.cancel("late"), "a late cancel must not trip the terminal guard on " + terminal);
            assertEquals(terminal, context.getState(), "and must not change the state");
            assertTrue(context.isCancelled(), "the token is still set, for anyone who consults it");
        }
    }

    @Test
    void completionAfterCancelKeepsCancelled_andRecordsNothingAsAViolation() {
        JobContext<String> context = freshContext();
        context.setState(JobState.RUNNING);
        context.cancel("first");
        assertDoesNotThrow(() -> context.complete("late result"));
        assertEquals(JobState.CANCELLED, context.getState(), "the first terminal transition wins");
    }

    @Test
    void theExplicitSetterStillRefusesASecondTerminal() {
        JobContext<String> context = freshContext();
        context.setState(JobState.RUNNING);
        context.setState(JobState.COMPLETED);
        assertThrows(IllegalStateException.class, () -> context.setState(JobState.FAILED),
                "the unconditional setter is for the dispatcher's own sequencing, where a second terminal is a bug");
    }

    @Test
    void aProgressReportUpgradesToRunningOnce_andNeverFromATerminal() {
        JobContext<String> context = freshContext();
        context.setState(JobState.QUEUED);
        context.publish("working", 10);
        assertEquals(JobState.RUNNING, context.getState());
        context.setState(JobState.COMPLETED);
        context.publish("late progress", 99);
        assertEquals(JobState.COMPLETED, context.getState(), "a progress report after completion changes nothing");
    }

    /** Never consults its token; finishes on its own schedule. */
    static class ObliviousJob extends AbstractJob<String> {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch mayFinish = new CountDownLatch(1);

        ObliviousJob() {
            super(ROOT, "oblivious-job");
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

    @Test
    void aCancelledJobThatFinishesAnyway_endsCancelledThroughTheDispatcher() throws Exception {
        ObliviousJob job = new ObliviousJob();
        JobHandle<String> handle = JobDispatcher.getInstance().submit(job);
        assertTrue(job.started.await(5, TimeUnit.SECONDS), "job did not start");
        JobDispatcher.getInstance().cancel(job.getId(), "external cancel");
        job.mayFinish.countDown();
        Throwable outcome = assertThrows(Throwable.class, handle::get);
        Throwable t = outcome;
        boolean cancellation = false;
        while (t != null) {
            if (t instanceof JobCancelledException || t instanceof java.util.concurrent.CancellationException) {
                cancellation = true;
            }
            // CancellationException is itself an IllegalStateException, so the guard is told apart by its message
            assertFalse(String.valueOf(t.getMessage()).contains("illegal state transition"),
                    "the terminal guard must not fire on the job's own thread: " + t);
            t = t.getCause();
        }
        assertTrue(cancellation, "the caller sees a cancellation, not a result and not a guard violation: " + outcome);
        // the dispatcher settles the state after the handle; give it the moment it needs
        for (int i = 0; i < 100 && handle.getContext().getState() != JobState.CANCELLED; i++) {
            Thread.sleep(20);
        }
        assertEquals(JobState.CANCELLED, handle.getContext().getState(), "the cancel that landed first is the recorded outcome");
    }

    /** Walks the cause chain for a JobCancelledException, the one shape every cancellation reaches a caller in. */
    private static JobCancelledException cancellationIn(Throwable outcome) {
        for (Throwable t = outcome; t != null; t = t.getCause()) {
            if (t instanceof JobCancelledException cancelled) {
                return cancelled;
            }
        }
        return null;
    }

    @Test
    void cancelThroughTheHandle_reachesTheCallerInTheSameShapeAsThroughTheDispatcher() throws Exception {
        ObliviousJob job = new ObliviousJob();
        JobHandle<String> handle = JobDispatcher.getInstance().submit(job);
        assertTrue(job.started.await(5, TimeUnit.SECONDS), "job did not start");
        assertTrue(handle.cancel("through the handle"), "a running job is found and its cancellation initiated");
        job.mayFinish.countDown();
        ExecutionException outcome = assertThrows(ExecutionException.class, handle::get,
                "the dispatcher settles the handle with the cancellation as its cause");
        JobCancelledException cancelled = cancellationIn(outcome);
        assertNotNull(cancelled, "the cause chain carries the runtime's own cancellation: " + outcome);
        assertInstanceOf(JobContext.CancellationException.class, cancelled.getCause());
        for (int i = 0; i < 100 && handle.getContext().getState() != JobState.CANCELLED; i++) {
            Thread.sleep(20);
        }
        assertEquals(JobState.CANCELLED, handle.getContext().getState());
    }

    /**
     * Polls its token the way the platform's long-running doers do: inside a catch-all that
     * funnels everything through unwrap. The cancellation must survive that funnel.
     */
    static class PollingDoer extends ai.redouble.nucleo.tools.AbstractDoer<Void, String> {
        final CountDownLatch started = new CountDownLatch(1);

        PollingDoer() {
            super(ROOT, "polling-doer");
        }

        @Override
        public String execute(JobContext<String> context) throws LLMReadableCheckedException {
            started.countDown();
            while (true) {
                try {
                    context.checkCancellation();
                    Thread.sleep(20);
                }
                catch (Exception e) {
                    throw LLMReadableCheckedException.unwrap(e);
                }
            }
        }
    }

    @Test
    void aCancellationSurvivesADoersCatchAll_andIsRecordedAsOne() throws Exception {
        PollingDoer doer = new PollingDoer();
        JobHandle<String> handle = JobDispatcher.getInstance().submit(doer);
        assertTrue(doer.started.await(5, TimeUnit.SECONDS), "doer did not start");
        assertTrue(JobDispatcher.getInstance().cancel(doer.getId(), "stop polling"));
        ExecutionException outcome = assertThrows(ExecutionException.class, handle::get);
        JobCancelledException cancelled = cancellationIn(outcome);
        assertNotNull(cancelled, "unwrap passed the cancellation through instead of burying it in a SystemException: " + outcome);
        assertInstanceOf(JobContext.CancellationException.class, cancelled.getCause());
        for (int i = 0; i < 100 && handle.getContext().getState() != JobState.CANCELLED; i++) {
            Thread.sleep(20);
        }
        assertEquals(JobState.CANCELLED, handle.getContext().getState(), "a cancellation, never a failure");
    }

    @Test
    void theRuntimesCancellationIsUncorrectable_andSitsInTheSealedHierarchy() {
        JobContext.CancellationException signal = new JobContext.CancellationException("why");
        assertInstanceOf(LLMReadableCheckedException.class, signal, "it propagates out of a tool's execute unwrapped");
        assertFalse(signal.isCorrectable(), "the job will not resume; the model takes another approach");
        assertSame(signal, LLMReadableCheckedException.unwrap(new RuntimeException(signal)),
                "unwrap finds it in a cause chain and returns it unchanged");
        assertTrue(signal.getLLMMessage().contains("why"));
    }
}
