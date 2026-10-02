/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.harness.observability.*;
import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a job can rely on from its {@link JobContext}: a blank user is refused; cancel and
 * timeout callbacks run once, and at once when registered late, whichever side of the firing
 * a registration lands on; {@code getRemainingTime} is
 * null without a deadline; {@code publishUserProgress} publishes {@code title: message} with
 * its percent and {@code publishUserNotification} the severity it was given;
 * {@code singleDependencyResult} refuses zero or several; the wait recordings sum per account
 * and as a wall wait; {@code currentJob()} is null on a thread outside any job and the job's
 * own context inside; the cancellation token runs a late callback at once.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class JobContextContractTest {
    private static final Identifiable ROOT = Job.workflow("context-user", "context-contract-test");

    @BeforeAll
    static void startDispatcher() {
        JobDispatcher.getInstance().start();
    }

    static class NoOpJob extends AbstractJob<String> {
        NoOpJob() {
            super(ROOT, "context-fixture");
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

    /** Reports whether the context it runs under is the one the thread marker names. */
    static class SelfAwareJob extends AbstractJob<Boolean> {
        SelfAwareJob() {
            super(ROOT, "self-aware");
        }

        @Override
        public JobRequirements getRequirements() {
            return new JobRequirements();
        }

        @Override
        public Boolean execute(JobResources resources, JobContext<Boolean> context) {
            return JobContext.currentJob() == context;
        }
    }

    static class EventSink implements JobObserver<JobEvent> {
        final List<JobEvent> seen = new CopyOnWriteArrayList<>();

        @Override
        public void observe(JobEvent event) {
            seen.add(event);
        }
    }

    private static JobContext<String> context(Duration timeout) {
        return new JobContext<>(new NoOpJob(), "context-user", timeout, new JobRequirements());
    }

    private static <E extends JobEvent> E awaitOne(EventSink sink, Class<E> type) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            Optional<JobEvent> found = sink.seen.stream().filter(type::isInstance).findFirst();
            if (found.isPresent()) {
                return type.cast(found.get());
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no " + type.getSimpleName() + " arrived");
    }

    @Test
    void aBlankUserIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new JobContext<>(new NoOpJob(), " ", null, null));
        assertThrows(IllegalArgumentException.class, () -> new JobContext<>(new NoOpJob(), null, null, null));
    }

    @Test
    void cancelCallbacksRunOnce_andAtOnceWhenRegisteredLate() {
        JobContext<String> context = context(null);
        AtomicInteger early = new AtomicInteger();
        context.onCancel(early::incrementAndGet);
        context.cancel("first");
        context.cancel("second");
        context.cancel();
        assertEquals(1, early.get(), "the callbacks run once however many times cancel arrives");
        AtomicInteger late = new AtomicInteger();
        context.onCancel(late::incrementAndGet);
        assertEquals(1, late.get(), "a callback registered after cancellation runs immediately");
        assertTrue(context.isCancelled());
        JobContext.CancellationException signal = assertThrows(JobContext.CancellationException.class, context::checkCancellation);
        assertTrue(signal.getLLMMessage().contains("cancelled"));
    }

    @Test
    void aCancelCallbackRegisteredWhileTheFiringIsUnderWayRunsOnce() {
        JobContext<String> context = context(null);
        AtomicInteger outer = new AtomicInteger();
        AtomicInteger inner = new AtomicInteger();
        context.onCancel(() -> {
            outer.incrementAndGet();
            // The token is already set here: the firing is under way
            context.onCancel(inner::incrementAndGet);
        });
        context.cancel("first");
        assertEquals(1, outer.get());
        assertEquals(1, inner.get(), "registered inside the firing: run by its registrar, and not again by the firing");
        context.cancel("second");
        assertEquals(1, inner.get());
    }

    @Test
    void cancelCallbacksRacingTheFiringRunEachExactlyOnce() throws Exception {
        int registrars = 8;
        for (int round = 0; round < 100; round++) {
            JobContext<String> context = context(null);
            AtomicInteger[] runs = new AtomicInteger[registrars];
            CountDownLatch start = new CountDownLatch(1);
            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < registrars; i++) {
                runs[i] = new AtomicInteger();
                AtomicInteger mine = runs[i];
                threads.add(Thread.ofVirtual().start(() -> {
                    await(start);
                    context.onCancel(mine::incrementAndGet);
                }));
            }
            Thread canceller = Thread.ofVirtual().start(() -> {
                await(start);
                context.cancel("race");
            });
            start.countDown();
            for (Thread t : threads) {
                t.join();
            }
            canceller.join();
            for (int i = 0; i < registrars; i++) {
                assertEquals(1, runs[i].get(), "round " + round + ", registrar " + i + ": a callback runs once, whichever side of the firing it lands on");
            }
        }
    }

    @Test
    void aTimeoutCallbackRegisteredWhileTheFiringIsUnderWayRunsOnce() throws Exception {
        JobContext<String> context = context(Duration.ofMillis(1));
        Thread.sleep(10);
        AtomicInteger outer = new AtomicInteger();
        AtomicInteger inner = new AtomicInteger();
        context.onTimeout(() -> {
            outer.incrementAndGet();
            context.onTimeout(inner::incrementAndGet);
        });
        assertEquals(1, outer.get(), "registered after the deadline, it ran at once");
        assertEquals(1, inner.get(), "registered inside the firing: run by its registrar, and not again");
        assertTrue(context.isTimedOut());
        assertEquals(1, inner.get());
        assertEquals(1, outer.get());
    }

    @Test
    void aTimeoutCallbackRegisteredAfterCompletionIsDroppedNotQueued() throws Exception {
        JobContext<String> context = context(Duration.ofMillis(1));
        context.complete("done");
        Thread.sleep(10);
        AtomicInteger runs = new AtomicInteger();
        context.onTimeout(runs::incrementAndGet);
        assertTrue(context.isTimedOut(), "the deadline has passed");
        assertEquals(0, runs.get(), "a completed job never fires its timeout callbacks, so the late one never runs");
        assertEquals(0, context.pendingTimeoutCallbacks(), "and nothing waits for a firing that will never come");
    }

    @Test
    void timeoutCallbacksRacingTheFirstObservationRunEachExactlyOnce() throws Exception {
        int registrars = 8;
        for (int round = 0; round < 100; round++) {
            JobContext<String> context = context(Duration.ofMillis(1));
            Thread.sleep(2);
            AtomicInteger[] runs = new AtomicInteger[registrars];
            CountDownLatch start = new CountDownLatch(1);
            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < registrars; i++) {
                runs[i] = new AtomicInteger();
                AtomicInteger mine = runs[i];
                threads.add(Thread.ofVirtual().start(() -> {
                    await(start);
                    context.onTimeout(mine::incrementAndGet);
                }));
            }
            Thread observer = Thread.ofVirtual().start(() -> {
                await(start);
                context.isTimedOut();
            });
            start.countDown();
            for (Thread t : threads) {
                t.join();
            }
            observer.join();
            for (int i = 0; i < registrars; i++) {
                assertEquals(1, runs[i].get(), "round " + round + ", registrar " + i + ": a callback runs once, whichever side of the first observation it lands on");
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void timeoutCallbacksRunOnceAfterTheDeadline_andAtOnceWhenRegisteredLate() throws Exception {
        JobContext<String> context = context(Duration.ofMillis(1));
        Thread.sleep(10);
        AtomicInteger runs = new AtomicInteger();
        context.onTimeout(runs::incrementAndGet);
        assertTrue(context.isTimedOut(), "the deadline has passed");
        assertEquals(1, runs.get(), "registered after the deadline: ran at once, and the second isTimedOut did not run it again");
        assertNotNull(context.getRemainingTime(), "a deadline means a remaining time, negative by now");
        assertTrue(context.getRemainingTime().isNegative());
        assertNull(context(null).getRemainingTime(), "no deadline, no remaining time");
        assertFalse(context(null).isTimedOut(), "no deadline never times out");
    }

    @Test
    void userProgressAndNotificationsPublishWhatTheySay() throws Exception {
        JobContext<String> context = context(null);
        EventSink sink = new EventSink();
        MessageBus.Subscription subscription = JobDispatcher.getInstance().observeWorkflow(ROOT.getWorkflowId(), sink);
        try {
            context.publishUserProgress("Indexing", "3 of 10", 30);
            JobProgressEvent<?> progress = awaitOne(sink, JobProgressEvent.class);
            assertEquals("Indexing: 3 of 10", progress.getPayload(), "title and message, joined");
            assertEquals(30, progress.getProgressPercent());
            assertEquals(30, context.getProgressPercent());
            assertEquals("Indexing: 3 of 10", context.getProgressMessage());
            context.publishUserNotification(UserNotificationEvent.Severity.WARNING, "Heads up", "something to know");
            UserNotificationEvent notification = awaitOne(sink, UserNotificationEvent.class);
            assertEquals(UserNotificationEvent.Severity.WARNING, notification.getSeverity(), "the severity asked for is the one published");
        }
        finally {
            subscription.unsubscribe();
        }
    }

    @Test
    void singleDependencyResultRefusesZeroOrSeveral() {
        JobContext<String> context = context(null);
        assertThrows(IllegalStateException.class, context::singleDependencyResult, "no dependency: nothing to return");
        JobSnapshot a = new JobSnapshot(new NoOpJob());
        JobSnapshot b = new JobSnapshot(new NoOpJob());
        Map<JobSnapshot, Object> two = new LinkedHashMap<>();
        two.put(a, "first");
        two.put(b, "second");
        context.setDependencyResults(two);
        assertThrows(IllegalStateException.class, context::singleDependencyResult, "several dependencies: the caller must pick");
        assertEquals(List.of("first", "second"), context.allDependencyResults(), "all results, in declaration order");
        context.setDependencyResults(Map.of(a, "only"));
        assertEquals("only", context.<String>singleDependencyResult());
        assertThrows(UnsupportedOperationException.class, () -> context.getDependencyResults().clear(), "the result map is read-only");
    }

    @Test
    void waitRecordingsSumPerAccountAndAsAWallWait() {
        JobContext<String> context = context(null);
        context.recordResourceWait("http", 100);
        context.recordResourceWait("http", 50);
        context.recordResourceWait("db:default", 7);
        context.recordAdmissionWait(120);
        context.recordAdmissionWait(30);
        @SuppressWarnings("unchecked")
        Map<String, Long> waits = (Map<String, Long>) context.getMetadata("wait_times");
        assertEquals(150L, waits.get("http"), "an account's waits sum");
        assertEquals(7L, waits.get("db:default"));
        assertEquals(150L, context.getMetadata("admission_wait_ms"), "the wall wait sums over attempts");
        assertEquals(150L, context.getSnapshot().getMetadata().get("admission_wait_ms"), "the snapshot carries the metadata");
    }

    @Test
    void currentJobIsNullOutsideAJobAndTheJobsOwnContextInside() throws Exception {
        assertNull(JobContext.currentJob(), "a test thread runs no job");
        assertFalse(JobContext.isExecutingJobWithResources());
        assertTrue(JobDispatcher.getInstance().submit(new SelfAwareJob()).get(), "inside execute the marker names this job's context");
    }

    @Test
    void theCancellationTokenRunsALateCallbackAtOnce() {
        JobContext<String> context = context(null);
        JobContext.CancellationToken token = new JobContext.CancellationToken();
        AtomicInteger runs = new AtomicInteger();
        token.onCancellation(runs::incrementAndGet);
        assertEquals(0, runs.get());
        token.cancel("done");
        token.cancel("again");
        assertEquals(1, runs.get(), "cancel notifies once");
        assertEquals("done", token.getReason(), "the first reason stands");
        token.onCancellation(runs::incrementAndGet);
        assertEquals(2, runs.get(), "a late callback runs at once");
        assertFalse(context.isCancelled(), "a token of its own does not touch the context");
    }
}
