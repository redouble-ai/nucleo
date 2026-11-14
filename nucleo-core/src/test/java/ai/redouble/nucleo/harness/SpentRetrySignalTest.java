/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.retry.*;
import ai.redouble.nucleo.mcp.server.fixtures.*;
import ai.redouble.nucleo.tools.*;
import org.junit.jupiter.api.*;

import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A retry signal belongs to the job whose body raised it, and to no job above. Pinned here on
 * the dispatcher itself, with jobs that await a child and pass its failure through
 * {@link LLMReadableCheckedException#unwrap}, the way every doer and thinker does:
 *
 * <ul>
 *   <li>a child that spends its upstream retries is run budget + 1 times, and every job above
 *       it runs once, at any depth;</li>
 *   <li>the failure that reaches the root is the child's, naming the child and its budget, with
 *       the provider's fault still in its cause chain;</li>
 *   <li>the same holds for every other way a job ends with a signal in hand: a second output
 *       truncation, the correction backstop, a time budget that ran out, a cancellation;</li>
 *   <li>no job's handle ever fails with a bare retry signal: what the dispatcher hands a
 *       handle is always a verdict;</li>
 *   <li>a signal raised in a job's own body, caught broadly and passed through {@code unwrap},
 *       still reaches that job's own dispatcher loop.</li>
 * </ul>
 *
 * @author Andrey Santrosyan
 * @since 0.1.3 (2026-11-14)
 */
class SpentRetrySignalTest {

    @BeforeAll
    static void startDispatcher() {
        JobDispatcher.getInstance().start();
    }

    private static Identifiable root() {
        return Job.workflow("spent-retry-signal-test", "spent-retry-signal-test");
    }

    private static TransientErrorRetryException fault() {
        return new TransientErrorRetryException("fixture upstream fault", "fixture", "boom", 503, 0, null);
    }

    /**
     * Awaits one child and passes whatever the child failed with through {@code unwrap}. It
     * carries an upstream budget of its own, so a signal rescued from the child's failure
     * would run this body again.
     */
    static final class AwaitingDoer extends AbstractDoer<NoFieldsInput, String> {
        final AtomicInteger bodies = new AtomicInteger();
        final CountDownLatch childSubmitted = new CountDownLatch(1);
        private final Function<Identifiable, Job<String>> childFactory;
        volatile Job<String> child;
        volatile JobHandle<String> childHandle;

        AwaitingDoer(Identifiable parent, int upstreamRetries, Function<Identifiable, Job<String>> childFactory) {
            super(parent);
            this.childFactory = childFactory;
            setInput(new NoFieldsInput());
            setUpstreamRetries(upstreamRetries);
        }

        @Override
        public String execute(JobContext<String> context) throws LLMReadableCheckedException {
            bodies.incrementAndGet();
            child = childFactory.apply(this);
            try {
                childHandle = submitInStep(child);
                childSubmitted.countDown();
                return childHandle.get();
            }
            catch (Exception e) {
                throw LLMReadableCheckedException.unwrap(e);
            }
        }
    }

    /** Raises the same signal on every attempt, the signal its constructor was given a source of. */
    static final class SignallingJob extends AbstractJob<String> {
        final AtomicInteger attempts = new AtomicInteger();
        private final Supplier<RuntimeException> signal;

        SignallingJob(Identifiable parent, Supplier<RuntimeException> signal) {
            super(parent, "signalling-fixture");
            this.signal = signal;
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
            throw signal.get();
        }
    }

    /**
     * Outlives its one-second time budget and then raises an upstream signal: the attempt the
     * dispatcher settles as a timeout, never as a retry. With {@code awaitCancelFirst} it first
     * waits for an outside cancel and ignores it, so the cancel is the earlier terminal event.
     */
    static final class OverdueSignallingJob extends AbstractJob<String> {
        final AtomicInteger attempts = new AtomicInteger();
        final CountDownLatch running = new CountDownLatch(1);
        private final boolean awaitCancelFirst;

        OverdueSignallingJob(Identifiable parent, boolean awaitCancelFirst) {
            super(parent, "overdue-signalling-fixture");
            this.awaitCancelFirst = awaitCancelFirst;
            setTimeout(Duration.ofSeconds(1));
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
            Instant started = Instant.now();
            running.countDown();
            if (awaitCancelFirst) {
                pauseUntil(context::isCancelled);
            }
            // 200 ms past the budget the cooperative phase has fired, and the aggressive one,
            // at least 500 ms later, has not
            pauseUntil(() -> Duration.between(started, Instant.now()).toMillis() >= 1_200);
            throw fault();
        }

        private static void pauseUntil(BooleanSupplier condition) {
            while (!condition.getAsBoolean()) {
                try {
                    Thread.sleep(10);
                }
                catch (InterruptedException e) {
                    // the fixture ignores its cancellation on purpose: it has to reach the throw
                }
            }
        }
    }

    /** A job whose own body catches broadly around a signal and passes it through {@code unwrap}. */
    static final class BroadCatchJob extends AbstractJob<String> {
        final AtomicInteger attempts = new AtomicInteger();

        BroadCatchJob(Identifiable parent, int upstreamRetries) {
            super(parent, "broad-catch-fixture");
            setUpstreamRetries(upstreamRetries);
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = new JobRequirements();
            req.setRequiresTransaction(false);
            return req;
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) throws LLMReadableCheckedException {
            attempts.incrementAndGet();
            try {
                throw new ExecutionException("a call made on another thread", fault());
            }
            catch (Exception e) {
                throw LLMReadableCheckedException.unwrap(e);
            }
        }
    }

    private static boolean isRetrySignal(Throwable t) {
        return t instanceof UpstreamRetryException || t instanceof ResponseCorrectionRetryException || t instanceof OutputTruncationRetryException;
    }

    /** The signal a failure still carries beneath its verdict, or null when its chain holds none. */
    private static Throwable signalBeneath(Throwable verdict) {
        for (Throwable current = verdict.getCause(); current != null; current = current.getCause()) {
            if (isRetrySignal(current)) {
                return current;
            }
        }
        return null;
    }

    private static Throwable failureOf(JobHandle<String> handle, long seconds) {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> handle.get(seconds, TimeUnit.SECONDS));
        assertFalse(isRetrySignal(failure.getCause()), "a handle never fails with a bare retry signal: " + failure.getCause());
        assertInstanceOf(LLMReadableException.class, failure.getCause(), "what the dispatcher hands a handle is a verdict: " + failure.getCause());
        return failure.getCause();
    }

    @Test
    void aChildThatSpendsItsRetries_runsEveryJobAboveItOnce() {
        AwaitingDoer grandparent = new AwaitingDoer(root(), 1,
                p -> new AwaitingDoer(p, 1, c -> new DispatchContractTest.FaultingJob(c, 0)));
        Throwable failure = failureOf(JobDispatcher.getInstance().submit(grandparent), 30);
        AwaitingDoer parent = (AwaitingDoer) grandparent.child;
        DispatchContractTest.FaultingJob child = (DispatchContractTest.FaultingJob) parent.child;
        assertEquals(1, child.attempts.get(), "the provider is called budget + 1 times: a budget of 0 is one call");
        assertEquals(1, parent.bodies.get(), "nothing in the parent's body failed, so it is not run again");
        assertEquals(1, grandparent.bodies.get(), "nor is the job above it");
        assertInstanceOf(UncorrectableRuntimeLLMException.class, failure, "the root fails with the child's exhausted failure: " + failure);
        assertTrue(failure.getMessage().contains("transparent retry budget (0)"), "naming the budget that was spent: " + failure.getMessage());
        assertTrue(failure.getMessage().contains(child.getClass().getSimpleName()), "and the job whose budget it was: " + failure.getMessage());
        assertInstanceOf(TransientErrorRetryException.class, signalBeneath(failure), "the provider's fault is still readable in the cause chain");
        assertSame(failureOf(parent.childHandle, 1), failure, "the failure travels up as the object the child failed with");
    }

    /** Sleeps one real pacing delay, five to thirty seconds: the only way to see a budget above zero spent. */
    @Test
    void aChildWithABudgetOfOne_isCalledTwiceAndEveryJobAboveItRunsOnce() {
        AwaitingDoer grandparent = new AwaitingDoer(root(), 1,
                p -> new AwaitingDoer(p, 1, c -> new DispatchContractTest.FaultingJob(c, 1)));
        Throwable failure = failureOf(JobDispatcher.getInstance().submit(grandparent), 90);
        AwaitingDoer parent = (AwaitingDoer) grandparent.child;
        DispatchContractTest.FaultingJob child = (DispatchContractTest.FaultingJob) parent.child;
        assertEquals(2, child.attempts.get(), "budget + 1 calls: the first and its one re-run");
        assertEquals(1, parent.bodies.get(), "the parent runs once");
        assertEquals(1, grandparent.bodies.get(), "and so does the job above it");
        assertTrue(failure.getMessage().contains("transparent retry budget (1)"), "the failure names the budget that was spent: " + failure.getMessage());
    }

    @Test
    void aSecondTruncationAndTheCorrectionBackstop_endTheChildAndRunTheParentOnce() {
        AwaitingDoer overTruncation = new AwaitingDoer(root(), 1,
                p -> new SignallingJob(p, () -> new OutputTruncationRetryException("fixture-model", 1000, 4000)));
        Throwable truncated = failureOf(JobDispatcher.getInstance().submit(overTruncation), 30);
        assertEquals(2, ((SignallingJob) overTruncation.child).attempts.get(), "one escalation, then the second truncation ends the job");
        assertEquals(1, overTruncation.bodies.get(), "the parent is not run again for a truncation its child already failed on");
        assertInstanceOf(OutputTruncationRetryException.class, signalBeneath(truncated));
        assertTrue(truncated.getMessage().contains(SignallingJob.class.getSimpleName()), "the failure names the job: " + truncated.getMessage());

        AwaitingDoer overCorrection = new AwaitingDoer(root(), 1,
                p -> new SignallingJob(p, () -> new ResponseCorrectionRetryException("fixture-model", 1, new JsonParseException("no object", null))));
        Throwable uncorrected = failureOf(JobDispatcher.getInstance().submit(overCorrection), 30);
        assertEquals(5, ((SignallingJob) overCorrection.child).attempts.get(), "the backstop lets four corrections through and refuses the fifth");
        assertEquals(1, overCorrection.bodies.get(), "the parent is not run again for corrections its child already spent");
        assertInstanceOf(ResponseCorrectionRetryException.class, signalBeneath(uncorrected));
        assertTrue(uncorrected.getMessage().contains(SignallingJob.class.getSimpleName()), "the failure names the job: " + uncorrected.getMessage());
    }

    @Test
    void aChildThatTimesOutWithASignalInHand_failsAsATimeoutAndRunsTheParentOnce() {
        AwaitingDoer parent = new AwaitingDoer(root(), 1, p -> new OverdueSignallingJob(p, false));
        Throwable failure = failureOf(JobDispatcher.getInstance().submit(parent), 30);
        assertInstanceOf(JobTimeoutException.class, failure, "the child's outcome is its timeout: " + failure);
        assertInstanceOf(TransientErrorRetryException.class, signalBeneath(failure), "with the signal it unwound on beneath it");
        assertEquals(1, ((OverdueSignallingJob) parent.child).attempts.get(), "a timed-out attempt is not retried");
        assertEquals(1, parent.bodies.get(), "and the signal under the timeout does not run the parent again");
    }

    @Test
    void aChildCancelledBeforeItTimesOutWithASignalInHand_failsAsCancelledAndRunsTheParentOnce() throws Exception {
        AwaitingDoer parent = new AwaitingDoer(root(), 1, p -> new OverdueSignallingJob(p, true));
        JobHandle<String> handle = JobDispatcher.getInstance().submit(parent);
        assertTrue(parent.childSubmitted.await(10, TimeUnit.SECONDS), "the parent submitted its child");
        assertTrue(((OverdueSignallingJob) parent.child).running.await(10, TimeUnit.SECONDS), "the child is running");
        assertTrue(parent.childHandle.cancel("cancelled by the test"), "the running child was found and cancelled");
        Throwable failure = failureOf(handle, 30);
        assertInstanceOf(JobCancelledException.class, failure, "the first terminal event wins, and it was the cancel: " + failure);
        assertInstanceOf(TransientErrorRetryException.class, signalBeneath(failure), "with the signal it unwound on beneath it");
        assertEquals(1, ((OverdueSignallingJob) parent.child).attempts.get(), "a cancelled attempt is not retried");
        assertEquals(1, parent.bodies.get(), "and the signal under the cancellation does not run the parent again");
    }

    @Test
    void aSignalRaisedInAJobsOwnBodyAndPassedThroughUnwrap_reachesThatJobsOwnLoop() {
        // Budget 0: the loop that receives the signal answers at once with its budget failure,
        // so the outcome says whether the signal arrived, without sleeping a pacing delay. Had
        // unwrap turned it into a failure of its own, the job would end as a SystemException.
        BroadCatchJob job = new BroadCatchJob(root(), 0);
        Throwable failure = failureOf(JobDispatcher.getInstance().submit(job), 30);
        assertEquals(1, job.attempts.get());
        assertInstanceOf(UncorrectableRuntimeLLMException.class, failure, "the job's own loop took the signal and found its budget spent: " + failure);
        assertTrue(failure.getMessage().contains("transparent retry budget (0)"), failure.getMessage());
        assertInstanceOf(TransientErrorRetryException.class, failure.getCause(), "the signal itself, out from under the raw wrapper");
    }
}
