/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.harness.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;
import static ai.redouble.nucleo.harness.admission.AdmissionFixtures.*;

/**
 * The memory gate inside a real {@link Admission}, on a shared fake clock and a fake heap
 * measured in gigabytes, so every scenario is stated in the terms the gate is judged by: how full
 * the heap reads, how many jobs admission holds on memory, and how that line drains.
 *
 * <p>The contract under test. Only resourceful jobs meet the gate; an orchestrator never does. A
 * lone arrival is never spaced, in any zone below the latch. A line held on some other account is
 * not memory's to pace. Memory's own line, the jobs the latch refused and the jobs that join them
 * while it drains, leaves one at a time: a completion or the cubic delay for the heap as it reads
 * now, whichever comes first, releases one, and a completion releases at most one. The latch, set
 * at 95%, holds every resourceful job, and when it clears the line leaves one at a time and never
 * as the pass that cleared it.
 *
 * <p>Time is advanced by the test and the evaluator is woken explicitly, exactly as the other
 * admission tests do, so a delay of 8.9 seconds costs no wall time. Positive claims wait for the
 * grant; negative claims assert that nothing changed within a short real window.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-08)
 */
class MemoryGateDrainTest {

    private static final List<Void> NONE = Collections.emptyList();
    private static final long RECHECK = TimeUnit.MILLISECONDS.toNanos(MemoryPressureGate.CRITICAL_CHECK_INTERVAL_MS);

    /** The gate over a fake heap in gigabytes and the clock admission shares. */
    static final class FakeHeapGate extends MemoryPressureGate {
        private final AtomicLong clock;
        volatile double maxGb = 100;
        volatile double usedGb;

        FakeHeapGate(AtomicLong clock) {
            this.clock = clock;
        }

        @Override
        double getHeapUsageRatio() {
            return usedGb / maxGb;
        }

        @Override
        long nanoTime() {
            return clock.get();
        }

        void wake() {
            capacityChanged();
        }
    }

    /** One transition admission reported on some account. */
    record Emitted(String account, LimiterEvent.Type type, long waitNanos) {
    }

    /** A line of jobs submitted in order, with what happened to each. */
    final class Line {
        final List<AtomicReference<Grant>> grants = new ArrayList<>();
        final List<AtomicLong> grantedAt = new ArrayList<>();
        final List<AtomicReference<Throwable>> failures = new ArrayList<>();
        final List<JobContext<String>> contexts = new ArrayList<>();
        final List<Thread> threads = new ArrayList<>();

        /**
         * Submits one job and returns once it is parked, granted or refused, so that submission
         * order is arrival order. Virtual threads started in a loop do not reach {@code admit} in
         * loop order on their own, and every ordering claim below rests on this. The count waited
         * on is parked plus this line's resolved, which grows by exactly one per submission even
         * when the evaluator grants one of this line's earlier jobs at the same moment.
         */
        void submit(Demand demand) throws InterruptedException {
            AtomicReference<Grant> out = new AtomicReference<>();
            AtomicLong at = new AtomicLong(-1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            JobContext<String> ctx = context();
            int accountedBefore = admission.queueSize() + granted() + failed();
            grants.add(out);
            grantedAt.add(at);
            failures.add(failure);
            contexts.add(ctx);
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    // The grant instant is the evaluator's, arrival plus the wait it computed on the
                    // shared clock, not the moment this thread happens to run afterwards.
                    long arrival = clock.get();
                    Grant grant = admission.admit(demand, ctx);
                    at.set(arrival + TimeUnit.MILLISECONDS.toNanos(grant.getWallWaitMs()));
                    out.set(grant);
                }
                catch (Throwable t) {
                    failure.set(t);
                }
            }));
            assertTrue(await(() -> admission.queueSize() + granted() + failed() > accountedBefore, 5_000),
                    "the submitted job neither parked nor resolved");
        }

        int granted() {
            int n = 0;
            for (AtomicReference<Grant> grant : grants) {
                if (grant.get() != null) {
                    n++;
                }
            }
            return n;
        }

        int failed() {
            int n = 0;
            for (AtomicReference<Throwable> failure : failures) {
                if (failure.get() != null) {
                    n++;
                }
            }
            return n;
        }

        /** Granted jobs, in submission order, were granted in that order and no later job overtook an earlier one still waiting. */
        void assertFifo() {
            long last = Long.MIN_VALUE;
            boolean sawUngranted = false;
            for (int i = 0; i < grants.size(); i++) {
                if (grants.get(i).get() == null) {
                    if (failures.get(i).get() == null) {
                        sawUngranted = true;
                    }
                    continue;
                }
                assertFalse(sawUngranted, "job " + i + " was granted while an earlier job is still waiting; " + stamps());
                long at = grantedAt.get(i).get();
                assertTrue(at >= last, "job " + i + " granted at " + at + " before job before it at " + last + "; " + stamps());
                last = at;
            }
        }

        /** Every job's grant instant by submission index, for a failure message that shows the actual release order. */
        String stamps() {
            StringBuilder sb = new StringBuilder("stamps by index:");
            for (int i = 0; i < grants.size(); i++) {
                long at = grantedAt.get(i).get();
                sb.append(' ').append(i).append('=').append(at < 0 ? "-" : (at - 1_000_000_000L) / 1_000_000L + "ms");
            }
            return sb.toString();
        }

        void release(int i) {
            admission.release(grants.get(i).get());
        }

        void releaseAllGranted() {
            for (AtomicReference<Grant> grant : grants) {
                if (grant.get() != null) {
                    admission.release(grant.get());
                }
            }
        }
    }

    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private final FakeHeapGate gate = new FakeHeapGate(clock);
    private final List<Emitted> emitted = Collections.synchronizedList(new ArrayList<>());
    private final Slots db = new Slots("db", 10_000);
    private Admission admission;

    @BeforeEach
    void start() {
        admission = new Admission(gate, clock::get, (a, s, w, t, wait, r, amt) -> emitted.add(new Emitted(a.limiterName(), t, wait)));
        admission.start();
    }

    @AfterEach
    void stop() {
        admission.stop();
    }

    private Demand job() {
        Demand demand = new Demand();
        demand.add(db, null);
        return demand;
    }

    private Demand jobBehind(Scripted bar) {
        Demand demand = job();
        demand.add(bar, null);
        return demand;
    }

    private static long delay(double ratio) {
        return MemoryPressureGate.calculateThrottleDelay(ratio).toNanos();
    }

    private static long ms(long millis) {
        return TimeUnit.MILLISECONDS.toNanos(millis);
    }

    /** Advances the shared clock and wakes the evaluator, as a deadline would. */
    private void tick(long nanos) {
        clock.addAndGet(nanos);
        gate.wake();
    }

    /** The heap reads {@code usedGb} from now on; nothing wakes the evaluator, exactly as a collection does not. */
    private void heap(double usedGb) {
        gate.usedGb = usedGb;
    }

    private void awaitGranted(Line line, int n) throws InterruptedException {
        assertTrue(await(() -> line.granted() >= n, 5_000), "expected " + n + " granted, saw " + line.granted() + " with " + admission.queueSize() + " parked");
        assertEquals(n, line.granted(), "more than " + n + " were released");
    }

    private void stillExactly(Line line, int n) throws InterruptedException {
        assertFalse(await(() -> line.granted() != n, 200), "expected the count to stay at " + n + ", it is " + line.granted());
    }

    private long memoryEvents(LimiterEvent.Type type) {
        synchronized (emitted) {
            return emitted.stream().filter(e -> e.account().equals("memory") && e.type() == type).count();
        }
    }

    /** A line of {@code n} jobs the latch refused, the latch having lasted long enough that no earlier release still paces anything. */
    private Line queuedUnderLatch(int n) throws InterruptedException {
        heap(96);
        Line line = new Line();
        for (int i = 0; i < n; i++) {
            line.submit(job());
        }
        assertTrue(await(() -> admission.queueSize() == n, 5_000), "all " + n + " parked under the latch");
        tick(TimeUnit.SECONDS.toNanos(60));
        assertEquals(0, line.granted(), "the latch grants nothing however long it lasts");
        return line;
    }

    /** A line of {@code n} jobs parked behind a short account, which is that account's line and not memory's. */
    private Line queuedBehind(Scripted bar, int n) throws InterruptedException {
        bar.mode = Scripted.Mode.SHORT;
        Line line = new Line();
        for (int i = 0; i < n; i++) {
            line.submit(jobBehind(bar));
        }
        assertTrue(await(() -> admission.queueSize() == n, 5_000), "all " + n + " parked behind " + bar.limiterName());
        return line;
    }

    private void open(Scripted bar) {
        bar.mode = Scripted.Mode.FITS;
        bar.wakeAll();
    }

    /** Memory's line of {@code n}, the latch cleared at {@code usedGb}, with one job already released. */
    private Line draining(int n, double usedGb) throws InterruptedException {
        Line line = queuedUnderLatch(n);
        heap(usedGb);
        tick(0);
        awaitGranted(line, 1);
        return line;
    }

    // ---------------------------------------------------------------- who meets the gate

    @Test
    void loneArrivalsAreNeverSpaced_atAnyHeapBelowTheLatch() throws Exception {
        for (double used : new double[] {10, 49, 80, 88, 90, 94}) {
            heap(used);
            Line line = new Line();
            for (int i = 0; i < 5; i++) {
                line.submit(job());
                awaitGranted(line, i + 1);
            }
            assertEquals(0, admission.queueSize(), "nobody queued at " + used + "G");
            line.releaseAllGranted();
        }
    }

    @Test
    void orchestratorsNeverMeetTheGate_underTheLatchOrDuringTheDrain() throws Exception {
        heap(96);
        Line coordinators = new Line();
        for (int i = 0; i < 3; i++) {
            coordinators.submit(new Demand());
        }
        awaitGranted(coordinators, 3);
        assertEquals(0, admission.queueSize(), "three orchestrators started under a set latch");
        Line line = queuedUnderLatch(3);
        heap(89);
        tick(0);
        awaitGranted(line, 1);
        coordinators.submit(new Demand());
        awaitGranted(coordinators, 4);
        stillExactly(line, 1);
        assertEquals(2, admission.queueSize(), "an orchestrator arriving mid-drain went straight through; the two tools still wait");
        assertEquals(3, memoryEvents(LimiterEvent.Type.HELD), "memory held the three tools and nothing else");
    }

    @Test
    void aLineHeldOnAnotherAccount_isNotMemorysToPace_atAnyHeap() throws Exception {
        for (double used : new double[] {79, 90, 94}) {
            Scripted bar = new Scripted("bar");
            heap(used);
            Line line = queuedBehind(bar, 25);
            open(bar);
            awaitGranted(line, 25);
            assertEquals(0, admission.queueSize(), "the whole line left at the bar's pace at " + used + "G");
            assertEquals(0, memoryEvents(LimiterEvent.Type.HELD), "and memory held nobody at " + used + "G");
            line.releaseAllGranted();
        }
    }

    // ---------------------------------------------------------------- the drain

    @Test
    void aCompletionReleasesTheNextOne_atOnce() throws Exception {
        Line line = draining(5, 89);
        heap(94);
        stillExactly(line, 1);
        line.release(0);
        awaitGranted(line, 2);
        stillExactly(line, 2);
        line.release(1);
        awaitGranted(line, 3);
        stillExactly(line, 3);
        line.assertFifo();
    }

    @Test
    void aCompletionReleasesAtMostOne() throws Exception {
        heap(50);
        Line running = new Line();
        for (int i = 0; i < 10; i++) {
            running.submit(job());
        }
        awaitGranted(running, 10);
        Line line = queuedUnderLatch(25);
        heap(89);
        tick(0);
        stillExactly(line, 0);
        assertTrue(gate.getStatus().pacingDelayMillis() > TimeUnit.MINUTES.toMillis(50), "thirty-nine points above the last two admissions: the delay channel is on the cap");
        heap(94);
        for (int i = 0; i < 10; i++) {
            running.release(i);
            awaitGranted(line, 1 + i);
            stillExactly(line, 1 + i);
        }
        assertEquals(15, admission.queueSize(), "ten completions, ten releases, one each");
        line.assertFifo();
    }

    @Test
    void theDelayReleasesTheNextOne_whenNothingFinishes() throws Exception {
        Line line = queuedUnderLatch(25);
        heap(89);
        tick(0);
        awaitGranted(line, 1);
        stillExactly(line, 1);
        long released = clock.get();
        assertEquals(Math.min(released + delay(0.89), released + RECHECK), gate.earliestFit(NONE, NONE), "the evaluator is told the next instant to look");
        tick(delay(0.89) - ms(1));
        stillExactly(line, 1);
        tick(ms(1));
        awaitGranted(line, 2);
        for (int expected = 3; expected <= 6; expected++) {
            tick(delay(0.89));
            awaitGranted(line, expected);
        }
        line.assertFifo();
        assertEquals(19, admission.queueSize());
        assertEquals(6, db.currentInUse(), "each released job holds exactly its own permit");
    }

    @Test
    void memorysLineWithTwentyGigabytesFree_stillLeavesOneAtATime() throws Exception {
        Line line = queuedUnderLatch(25);
        assertTrue(await(() -> memoryEvents(LimiterEvent.Type.HELD) == 25, 5_000), "the latch held all twenty-five on memory, saw " + memoryEvents(LimiterEvent.Type.HELD));
        heap(79);
        tick(0);
        awaitGranted(line, 1);
        stillExactly(line, 1);
        tick(ms(49));
        stillExactly(line, 1);
        tick(ms(1));
        awaitGranted(line, 2);
        for (int expected = 3; expected <= 25; expected++) {
            tick(ms(50));
            awaitGranted(line, expected);
        }
        line.assertFifo();
        assertEquals(0, admission.queueSize());
        assertEquals(25, memoryEvents(LimiterEvent.Type.GRANTED_FROM_HOLD), "each was reported released from the memory hold");
    }

    @Test
    void aJobArrivingWhileMemorysLineDrains_joinsTheBackOfIt() throws Exception {
        Line line = draining(3, 89);
        line.submit(job());
        assertTrue(await(() -> admission.queueSize() == 3, 5_000), "the newcomer is parked behind the two still in the line");
        stillExactly(line, 1);
        assertEquals(4, memoryEvents(LimiterEvent.Type.HELD), "three held by the latch and the newcomer held on memory like the rest");
        line.release(0);
        awaitGranted(line, 2);
        tick(delay(0.89));
        awaitGranted(line, 3);
        tick(delay(0.89));
        awaitGranted(line, 4);
        line.assertFifo();
    }

    // ---------------------------------------------------------------- the heap moves

    @Test
    void aLineAtNinetyFourDrainsOnCompletions_notOnTheTwentyFourSecondDelay() throws Exception {
        // A long line at a frozen 94.8% reading paced by the delay alone would release one job
        // every 28.7 seconds. Each released job finishes in seconds; its completion releases
        // the next, and the drain runs at the jobs' own pace.
        Line line = draining(25, 89);
        heap(94.8);
        long first = clock.get();
        assertTrue(delay(0.948) > TimeUnit.SECONDS.toNanos(28), "the 94.8% delay is about 28.8 seconds");
        for (int i = 0; i < 20; i++) {
            tick(TimeUnit.SECONDS.toNanos(3));
            line.release(i);
            awaitGranted(line, 2 + i);
        }
        assertTrue(clock.get() - first < TimeUnit.SECONDS.toNanos(28 * 3), "twenty released in a minute, not ten minutes");
        line.assertFifo();
    }

    @Test
    void aCollectionMidDelay_isSeenAtTheRecheck() throws Exception {
        Line line = draining(5, 89);
        long released = clock.get();
        heap(94);
        assertEquals(released + RECHECK, gate.earliestFit(NONE, NONE), "the evaluator parks for the re-check, not the delay");
        tick(RECHECK - ms(100));
        stillExactly(line, 1);
        heap(30);
        tick(ms(100));
        awaitGranted(line, 2);
        assertEquals(released + RECHECK, line.grantedAt.get(1).get(), "released at the re-check, two seconds after the first, not twenty-four");
        tick(ms(50));
        awaitGranted(line, 3);
    }

    @Test
    void theDelayIsTheHeapNow_notTheHeapAtTheLastRelease() throws Exception {
        Line line = draining(3, 89);
        heap(90);
        tick(ms(1_200));
        stillExactly(line, 1);
        heap(85);
        tick(0);
        awaitGranted(line, 2);
    }

    @Test
    void aLatchDoesNotClearAtNinetyFour_orAtNinety_onlyBelow() throws Exception {
        Line line = queuedUnderLatch(3);
        heap(94);
        tick(RECHECK);
        stillExactly(line, 0);
        heap(90);
        tick(RECHECK);
        stillExactly(line, 0);
        assertEquals(clock.get() + RECHECK, gate.earliestFit(NONE, NONE), "still latched, still on the two-second re-check");
        heap(89.9);
        tick(RECHECK);
        awaitGranted(line, 1);
        stillExactly(line, 1);
    }

    @Test
    void theLatchStopsADrain_evenOnACompletion_andTheDrainResumesWhenItClears() throws Exception {
        Line line = draining(6, 89);
        line.release(0);
        awaitGranted(line, 2);
        heap(96);
        line.release(1);
        stillExactly(line, 2);
        assertEquals(clock.get() + RECHECK, gate.earliestFit(NONE, NONE), "latched: the timed re-check");
        heap(92);
        tick(RECHECK);
        stillExactly(line, 2);
        heap(89);
        tick(RECHECK);
        awaitGranted(line, 3);
        line.assertFifo();
    }

    @Test
    void aClimbOfTheFloor_isPunished_heldWhereItStops_andTheRefillAfterACollectionIsNotGrowth() throws Exception {
        Line line = draining(6, 80);
        heap(85);
        tick(0);
        stillExactly(line, 1);
        heap(83);
        tick(delay(0.85));
        awaitGranted(line, 2);
        heap(88);
        tick(0);
        stillExactly(line, 2);
        heap(86);
        tick(delay(0.88));
        stillExactly(line, 2);
        assertEquals(64.0, gate.getStatus().growthPenalty(), 0.001, "floors 80, 83, 86: six points over the last two releases");
        tick(64 * delay(0.86) - delay(0.88) - ms(1));
        stillExactly(line, 2);
        tick(ms(1));
        awaitGranted(line, 3);
        heap(83);
        tick(RECHECK);
        stillExactly(line, 3);
        assertEquals(64.0, gate.getStatus().growthPenalty(), 0.001, "a collection back to 83, the floor of two releases ago: the sixty-four is holding, it stays");
        tick(64 * delay(0.83) - RECHECK);
        awaitGranted(line, 4);
        heap(94);
        tick(RECHECK);
        stillExactly(line, 4);
        assertEquals(0.83, gate.getStatus().floorRatio(), 0.0001, "eleven points of garbage since the collection: the floor is unmoved");
        assertEquals(8.0, gate.getStatus().growthPenalty(), 0.001, "the refill prices nothing; the floor is three under the one two releases ago, so the sixty-four is an eight");
        heap(30);
        tick(RECHECK);
        awaitGranted(line, 5);
        assertEquals(1.0, gate.getStatus().growthPenalty(), 0.001, "the collection took the climb away");
    }

    @Test
    void aRisingHeapMidDrain_lengthensTheNextWait() throws Exception {
        Line line = draining(4, 85);
        heap(92);
        tick(delay(0.85));
        stillExactly(line, 1);
        tick(delay(0.92) - delay(0.85));
        awaitGranted(line, 2);
    }

    // ---------------------------------------------------------------- the line changes

    @Test
    void cancellingAParkedJobMidDrain_letsTheNextTakeItsTurn() throws Exception {
        Line line = draining(5, 89);
        TestDoors.cancel(line.contexts.get(1), "test");
        line.threads.get(1).join(5_000);
        assertTrue(line.failures.get(1).get() instanceof CancellationException);
        assertTrue(await(() -> admission.queueSize() == 3, 5_000), "the cancelled job has left the line");
        tick(delay(0.89));
        awaitGranted(line, 2);
        assertNotNull(line.grants.get(2).get(), "the job behind the cancelled one took the turn");
        assertNull(line.grants.get(3).get());
    }

    @Test
    void stopMidDrain_failsEveryParkedJob_andNothingHangs() throws Exception {
        Line line = draining(5, 89);
        admission.stop();
        for (Thread thread : line.threads) {
            thread.join(5_000);
        }
        assertEquals(1, line.granted());
        assertEquals(4, line.failed());
        for (int i = 1; i < 5; i++) {
            assertTrue(line.failures.get(i).get() instanceof CancellationException, "job " + i + ": " + line.failures.get(i).get());
        }
    }

    // ---------------------------------------------------------------- the walkthrough, 100G heap, 1G per job

    @Test
    void walkthrough_fiftiethJobAtFortyNineGigabytes_startsAtOnce() throws Exception {
        heap(49);
        Line line = new Line();
        line.submit(job());
        awaitGranted(line, 1);
        assertEquals(clock.get(), line.grantedAt.get(0).get());
    }

    @Test
    void walkthrough_ninetyFifthJobJoiningMemorysLine_atNinetyFour() throws Exception {
        Line line = draining(4, 89);
        heap(94);
        line.submit(job());
        assertTrue(await(() -> admission.queueSize() == 4, 5_000));
        stillExactly(line, 1);
        assertEquals(clock.get() + RECHECK, gate.earliestFit(NONE, NONE), "next look in two seconds, the delay itself is about 24");
        line.release(0);
        awaitGranted(line, 2);
        line.assertFifo();
    }

    @Test
    void walkthrough_twentyQueuedAtTheLatch_nobodyStartsUntilItReadsBelowNinety() throws Exception {
        heap(50);
        Line running = new Line();
        for (int i = 0; i < 20; i++) {
            running.submit(job());
        }
        awaitGranted(running, 20);
        heap(95);
        Line line = new Line();
        for (int i = 0; i < 20; i++) {
            line.submit(job());
        }
        assertTrue(await(() -> admission.queueSize() == 20, 5_000));
        for (int i = 0; i < 5; i++) {
            running.release(i);
            heap(95 - (i + 1));
            tick(RECHECK);
            stillExactly(line, 0);
        }
        assertEquals(90, gate.usedGb, 0.0001);
        running.release(5);
        heap(89);
        tick(RECHECK);
        awaitGranted(line, 1);
        stillExactly(line, 1);
    }

    @Test
    void walkthrough_twentyFiveQueued_atEightyNine_seventyNine_andFifty() throws Exception {
        Line at89 = queuedUnderLatch(25);
        heap(89);
        tick(0);
        awaitGranted(at89, 1);
        tick(delay(0.89) - ms(1));
        stillExactly(at89, 1);
        tick(ms(1));
        awaitGranted(at89, 2);
        heap(79);
        tick(ms(49));
        stillExactly(at89, 2);
        tick(ms(1));
        awaitGranted(at89, 3);
        heap(50);
        tick(ms(50));
        awaitGranted(at89, 4);
        tick(ms(50));
        awaitGranted(at89, 5);
        at89.assertFifo();
        assertEquals(20, admission.queueSize(), "twenty still waiting, released one per fifty milliseconds and never together");
    }

    // ---------------------------------------------------------------- the lost wakeup

    /**
     * Marking a long line held is not free: every first hold on an account emits an event. Here
     * each such emission on memory moves the clock one millisecond, so the 50ms floor delay closes
     * partway through the evaluator's walk over the line, as a long line on a real clock makes
     * it. The deadline the evaluator computes after the walk must then be
     * the closed instant, never null: null parks it with a full line, and with nothing running
     * there is no event left to wake it.
     */
    @Test
    void aDelayClosingWhileTheLineIsMarked_doesNotStrandTheEvaluator() throws Exception {
        admission.stop();
        AtomicInteger memoryHolds = new AtomicInteger();
        admission = new Admission(gate, clock::get, (a, s, w, t, wait, r, amt) -> {
            if (t == LimiterEvent.Type.HELD && a.limiterName().equals("memory")) {
                clock.addAndGet(ms(1));
                memoryHolds.incrementAndGet();
            }
        });
        admission.start();
        Line line = queuedUnderLatch(2);
        // a job is parked by the thread that submitted it and marked held by the evaluator's
        // pass over the queue, so the mark trails the parking: awaited, as each arrival's is below
        assertTrue(await(() -> memoryHolds.get() == 2, 2_000), "both parked jobs are marked held on memory by the evaluator's pass");
        int heldUnderLatch = memoryHolds.get();
        heap(1);
        tick(0);
        awaitGranted(line, 1);
        long released = clock.get();
        int arrivals = 0;
        while (clock.get() - released < delay(0.01)) {
            line.submit(job());
            arrivals++;
            int marked = heldUnderLatch + arrivals;
            assertTrue(await(() -> memoryHolds.get() == marked, 2_000), "each arrival's pass marks it held on memory");
        }
        assertTrue(await(() -> line.granted() >= 2, 3_000),
                "evaluator asleep with " + admission.queueSize() + " parked, the delay closed and nothing running: a lost wakeup");
        assertEquals(2, line.granted(), "and the line drains one at a time from there");
    }

    // ---------------------------------------------------------------- random shapes

    @Test
    void randomHeapAndCompletions_everyJobIsGranted_andNoPermitLeaks() throws Exception {
        Random random = new Random(20260908);
        int jobs = 150;
        CountDownLatch done = new CountDownLatch(jobs);
        AtomicInteger granted = new AtomicInteger();
        AtomicInteger overdrawn = new AtomicInteger();
        heap(40 + random.nextInt(50));
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < jobs; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    Grant grant = admission.admit(job(), context());
                    granted.incrementAndGet();
                    if (db.currentInUse() > db.capacity() || db.currentInUse() < 0) {
                        overdrawn.incrementAndGet();
                    }
                    Thread.sleep(1 + random.nextInt(3));
                    admission.release(grant);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                finally {
                    done.countDown();
                }
            }));
        }
        Thread driver = Thread.ofVirtual().start(() -> {
            int step = 0;
            while (done.getCount() > 0) {
                step++;
                if (step % 7 == 0) {
                    heap(40 + random.nextInt(58));
                }
                if (step > 400) {
                    heap(Math.min(gate.usedGb, 89));
                }
                tick(ms(20 + random.nextInt(200)));
                try {
                    Thread.sleep(2);
                }
                catch (InterruptedException e) {
                    return;
                }
            }
        });
        assertTrue(done.await(60, TimeUnit.SECONDS), "every job is eventually granted; " + admission.queueSize() + " still parked at " + gate.usedGb + "G");
        driver.interrupt();
        assertEquals(jobs, granted.get());
        assertEquals(0, overdrawn.get());
        assertEquals(0, db.currentInUse(), "every permit came back");
    }
}
