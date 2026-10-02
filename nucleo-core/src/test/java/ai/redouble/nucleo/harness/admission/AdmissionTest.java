/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.http.*;
import org.junit.jupiter.api.*;
import org.slf4j.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

import static org.junit.jupiter.api.Assertions.*;
import static ai.redouble.nucleo.harness.admission.AdmissionFixtures.*;

/**
 * The admission monitor's guarantees, each as one scenario: nothing is held while waiting, the
 * head is never starved, disjoint demands pass a long line, grants cascade in arrival order,
 * the clock alone wakes a metered wait, a capacity change wakes a parked job, faults refuse one
 * waiter only while a defect fails every waiter, cancellation and shutdown empty the queue,
 * grants settle once, and none of it knows the shape of a demand.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
class AdmissionTest {
    private static final Logger log = LoggerFactory.getLogger(AdmissionTest.class);

    private final MemoryFake memory = new MemoryFake();
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private Admission admission;

    @BeforeEach
    void start() {
        admission = new Admission(memory, clock::get, (a, s, w, t, wait, r, amt) -> { });
        admission.start();
    }

    @AfterEach
    void stop() {
        admission.stop();
    }

    private Demand tokensAnd(Meter meter, int tokens, RateLimiter<Void> gate) {
        Demand demand = new Demand();
        demand.add(meter, tokens);
        demand.add(gate, null);
        return demand;
    }

    private Demand tokens(Meter meter, int tokens) {
        Demand demand = new Demand();
        demand.add(meter, tokens);
        return demand;
    }

    private Thread admitAsync(Demand demand, JobContext<?> context, AtomicReference<Grant> out, AtomicReference<Throwable> failure) {
        return Thread.ofVirtual().start(() -> {
            try {
                out.set(admission.admit(demand, context));
            }
            catch (Throwable t) {
                failure.set(t);
            }
        });
    }

    @Test
    void aPeggedBucketWithAThousandWaiters_holdsZeroPermits() throws Exception {
        Meter bucket = new Meter("bucket", 100_000, 0, clock::get);
        bucket.drain();
        Slots http = new Slots("http", 1000);
        List<AtomicReference<Grant>> grants = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            AtomicReference<Grant> out = new AtomicReference<>();
            grants.add(out);
            admitAsync(tokensAnd(bucket, 100, http), context(), out, new AtomicReference<>());
        }
        assertTrue(await(() -> admission.queueSize() == 1000, 10_000), "every job parks in admission");

        assertEquals(0, http.currentInUse(), "a job waiting for tokens holds no permit");

        bucket.give(List.of(100_000));
        assertTrue(await(() -> grants.stream().allMatch(g -> g.get() != null), 20_000), "once tokens exist every job is granted");
        assertEquals(1000, http.currentInUse(), "and each granted job holds exactly its one permit");
    }

    @Test
    void nothingIsDebitedWhileWaitingForAPermit() throws Exception {
        // Tokens taken and then a wait for a permit would land the debit in an earlier window
        // than the send; the bucket is untouched until the whole demand fits.
        Meter bucket = new Meter("bucket", 1_000, 0, clock::get);
        Slots permit = new Slots("permit", 1);
        Grant holder = admission.admit(demand(permit), context());
        AtomicReference<Grant> out = new AtomicReference<>();
        admitAsync(tokensAnd(bucket, 400, permit), context(), out, new AtomicReference<>());
        assertTrue(await(() -> admission.queueSize() == 1, 5_000));

        assertEquals(1_000, (int) bucket.level(), "no token is debited while the job waits for the permit");
        assertEquals(0, bucket.takes.get());

        admission.release(holder);
        assertTrue(await(() -> out.get() != null, 5_000));
        assertEquals(600, (int) bucket.level(), "the debit lands at the grant, the instant before the send");
    }

    @Test
    void aPartialTakeIsUndoneInsideTheCriticalSection() throws Exception {
        // Have one, want the other: when the second account declines the take after the
        // pre-check passed (a cap clamped in between), what the first account gave is returned
        // before anyone else can observe it, and nothing is held.
        Slots first = new Slots("first", 1);
        Scripted second = new Scripted("second");
        second.mode = Scripted.Mode.TAKE_FAILS;
        AtomicReference<Grant> out = new AtomicReference<>();
        admitAsync(demand(first, second), context(), out, new AtomicReference<>());
        assertTrue(await(() -> admission.queueSize() == 1, 5_000));

        // the give-back happens inside the evaluator's critical section, which this thread is
        // outside of: a read that lands between the take and the give-back sees the permit
        // held for the instant it is. Passes run only on a wake, so once the pass the enqueue
        // woke is over the permit stays back until the account is woken again below.
        assertTrue(await(() -> first.currentInUse() == 0, 5_000), "the first account's permit was given back inside the same critical section");
        assertEquals(0, first.currentInUse());
        assertNull(out.get());

        second.mode = Scripted.Mode.FITS;
        second.wakeAll();
        assertTrue(await(() -> out.get() != null, 5_000), "once both fit together the whole demand is taken");
        assertEquals(1, first.currentInUse());
        assertEquals(1, second.takes.get());
    }

    @Test
    void holdAndWaitIsAbsent_aWaiterShortOnOneAccountHoldsNothingOnAnother() throws Exception {
        Slots plentiful = new Slots("plentiful", 100);
        Slots scarce = new Slots("scarce", 1);
        Grant holder = admission.admit(demand(scarce), context());
        for (int i = 0; i < 20; i++) {
            admitAsync(demand(plentiful, scarce), context(), new AtomicReference<>(), new AtomicReference<>());
        }
        assertTrue(await(() -> admission.queueSize() == 20, 5_000));

        assertEquals(0, plentiful.currentInUse(), "twenty jobs short on the scarce account hold none of the plentiful one");
        admission.release(holder);
    }

    @Test
    void aLargeHeadBehindAStreamOfSmallJobs_isGrantedWhenItsAmountAccumulates() throws Exception {
        Meter bucket = new Meter("bucket", 1_000, 1_000, clock::get);
        bucket.drain();
        AtomicReference<Grant> head = new AtomicReference<>();
        admitAsync(tokens(bucket, 600), context(), head, new AtomicReference<>());
        assertTrue(await(() -> admission.queueSize() == 1, 5_000));
        List<AtomicReference<Grant>> smalls = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            AtomicReference<Grant> out = new AtomicReference<>();
            smalls.add(out);
            admitAsync(tokens(bucket, 300), context(), out, new AtomicReference<>());
        }
        assertTrue(await(() -> admission.queueSize() == 6, 5_000));

        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(350));
        bucket.wake();
        assertTrue(await(() -> bucket.level() >= 300, 1_000));
        assertTrue(smalls.stream().allMatch(g -> g.get() == null), "at 350 tokens nobody behind the head may take below its reservation of 600");

        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(300));
        bucket.wake();
        assertTrue(await(() -> head.get() != null, 5_000), "the head is granted the moment its own amount has accumulated");
        assertTrue(smalls.stream().allMatch(g -> g.get() == null), "the smalls wait their turn behind it");

        for (int tick = 0; tick < 20 && !smalls.stream().allMatch(g -> g.get() != null); tick++) {
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(500));
            bucket.wake();
            await(() -> smalls.stream().allMatch(g -> g.get() != null), 200);
        }
        assertTrue(smalls.stream().allMatch(g -> g.get() != null), "then the stream flows at the refill rate");
    }

    @Test
    void aDisjointDemandBehindALongLine_isGrantedAtTheFirstPass() throws Exception {
        Meter bucket = new Meter("bucket", 1_000, 0, clock::get);
        bucket.drain();
        for (int i = 0; i < 50; i++) {
            admitAsync(tokens(bucket, 100), context(), new AtomicReference<>(), new AtomicReference<>());
        }
        assertTrue(await(() -> admission.queueSize() == 50, 5_000));
        Slots other = new Slots("other", 1);

        long start = System.nanoTime();
        Grant grant = admission.admit(demand(other), context());

        assertNotNull(grant);
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2), "a demand disjoint from the head's short account passes the line");
        assertEquals(50, admission.queueSize(), "the line itself is untouched");
    }

    @Test
    void oneGive_cascadesGrantsInArrivalOrder() throws Exception {
        Slots slots = new Slots("slots", 3);
        List<Grant> holders = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            holders.add(admission.admit(demand(slots), context()));
        }
        List<Integer> order = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 3; i++) {
            int id = i;
            AtomicReference<Grant> out = new AtomicReference<>();
            Thread.ofVirtual().start(() -> {
                try {
                    out.set(admission.admit(demand(slots), context()));
                    order.add(id);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(await(() -> admission.queueSize() == id + 1, 5_000), "waiters queue in arrival order");
        }

        for (int i = 0; i < 3; i++) {
            admission.release(holders.get(i));
            int expected = i + 1;
            assertTrue(await(() -> order.size() == expected, 5_000), "one permit back grants exactly one waiter");
        }
        assertEquals(List.of(0, 1, 2), order, "grants go to the oldest waiter first, every time");
    }

    @Test
    void interruptRemovesTheWaiter_andTheNextAdmitProceeds() throws Exception {
        Slots slot = new Slots("slot", 1);
        Grant holder = admission.admit(demand(slot), context());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread waiter = admitAsync(demand(slot), context(), new AtomicReference<>(), failure);
        assertTrue(await(() -> admission.queueSize() == 1, 5_000));

        waiter.interrupt();
        waiter.join(5_000);

        assertTrue(failure.get() instanceof InterruptedException);
        assertEquals(0, admission.queueSize());
        admission.release(holder);
        assertNotNull(admission.admit(demand(slot), context()), "the slot is free for the next job");
    }

    @Test
    void aTimeShortfall_isGrantedAtItsComputedDeadline_withNoGiveAtAll() throws Exception {
        TokenBucketRateLimiter bucket = new TokenBucketRateLimiter("real", new TokenBucketRateLimiter.Config(6_000, 60_000));
        Admission real = new Admission(memory, System::nanoTime, (a, s, w, t, wait, r, amt) -> { });
        real.start();
        try {
            Demand drain = new Demand();
            drain.add(bucket, 60_000);
            real.admit(drain, context());
            Demand need = new Demand();
            need.add(bucket, 100);
            long start = System.nanoTime();
            Grant grant = real.admit(need, context());
            long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertNotNull(grant);
            assertTrue(waitedMs >= 50, "the tokens came from the refill, not from thin air; waited " + waitedMs + "ms");
            assertTrue(waitedMs < 3_000, "the evaluator woke at the computed deadline; waited " + waitedMs + "ms");
        }
        finally {
            real.stop();
        }
    }

    @Test
    void aCapRaisedThroughSetConfig_wakesAndGrants() throws Exception {
        TokenBucketRateLimiter bucket = new TokenBucketRateLimiter("real", new TokenBucketRateLimiter.Config(60, 1_000));
        Admission real = new Admission(memory, System::nanoTime, (a, s, w, t, wait, r, amt) -> { });
        real.start();
        try {
            Demand drain = new Demand();
            drain.add(bucket, 1_000);
            real.admit(drain, context());
            Demand need = new Demand();
            need.add(bucket, 500);
            AtomicReference<Grant> out = new AtomicReference<>();
            Thread waiter = Thread.ofVirtual().start(() -> {
                try {
                    out.set(real.admit(need, context()));
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(await(() -> real.queueSize() == 1, 5_000), "at 1000 tokens per minute the job would wait half a minute");

            bucket.setConfig(new TokenBucketRateLimiter.Config(60_000, 60_000_000));
            waiter.join(5_000);

            assertNotNull(out.get(), "the raised rate moved the deadline to now and the evaluator woke");
        }
        finally {
            real.stop();
        }
    }

    @Test
    void anAccountRefusingFromFits_rejectsOnlyThatWaiter() throws Exception {
        Slots slot = new Slots("slot", 1);
        Scripted circuit = new Scripted("circuit");
        Grant holder = admission.admit(demand(slot), context());
        AtomicReference<Grant> patient = new AtomicReference<>();
        admitAsync(demand(slot), context(), patient, new AtomicReference<>());
        assertTrue(await(() -> admission.queueSize() == 1, 5_000));
        circuit.mode = Scripted.Mode.REFUSE;
        AtomicReference<Throwable> refused = new AtomicReference<>();
        Thread refusedWaiter = admitAsync(demand(slot, circuit), context(), new AtomicReference<>(), refused);

        refusedWaiter.join(5_000);

        assertTrue(refused.get() instanceof UncorrectableRuntimeLLMException, "the refused waiter fails with the account's message: " + refused.get());
        assertEquals(1, admission.queueSize(), "the patient waiter is untouched");
        admission.release(holder);
        assertTrue(await(() -> patient.get() != null, 5_000));
    }

    @Test
    void anAccountThrowingABug_rejectsOnlyThatWaiter_andTheEvaluatorSurvives() throws Exception {
        Slots slot = new Slots("slot", 1);
        Scripted broken = new Scripted("broken");
        Grant holder = admission.admit(demand(slot), context());
        broken.mode = Scripted.Mode.BROKEN;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread victim = admitAsync(demand(slot, broken), context(), new AtomicReference<>(), failure);
        victim.join(5_000);
        assertTrue(failure.get() instanceof UncorrectableRuntimeLLMException);
        assertTrue(failure.get().getCause() instanceof IllegalStateException);

        admission.release(holder);
        assertNotNull(admission.admit(demand(slot), context()), "the evaluator keeps granting after an account bug");
    }

    @Test
    void aDefectInsideAPass_failsEveryParkedJob_andClosesAdmission() throws Exception {
        Scripted first = new Scripted("first");
        Scripted second = new Scripted("second");
        second.mode = Scripted.Mode.SHORT;
        AtomicReference<Throwable> headFailure = new AtomicReference<>();
        Thread head = admitAsync(demand(first, second), context(), new AtomicReference<>(), headFailure);
        Slots full = new Slots("full", 1);
        Grant holder = admission.admit(demand(full), context());
        AtomicReference<Throwable> bystanderFailure = new AtomicReference<>();
        Thread bystander = admitAsync(demand(full), context(), new AtomicReference<>(), bystanderFailure);
        assertTrue(await(() -> admission.queueSize() == 2, 5_000), "both parked, holding nothing");

        // On the next pass the head takes first, second declines the take, and first cannot give
        // back what the attempt took. That is not an account's answer about a waiter; it is a defect.
        second.mode = Scripted.Mode.TAKE_FAILS;
        first.giveThrows = true;
        second.wakeAll();
        head.join(5_000);
        bystander.join(5_000);
        assertTrue(headFailure.get() instanceof UncorrectableRuntimeLLMException, "the head fails with the defect");
        assertTrue(headFailure.get().getCause() instanceof IllegalStateException, "carrying the defect as its cause");
        assertTrue(bystanderFailure.get() instanceof UncorrectableRuntimeLLMException, "every parked job fails, not only the one being evaluated");
        assertTrue(bystanderFailure.get().getCause() == headFailure.get().getCause(), "with the same defect as cause");
        assertEquals(0, admission.queueSize(), "nobody is left hanging");

        admission.release(holder);
        UncorrectableRuntimeLLMException later = assertThrows(UncorrectableRuntimeLLMException.class,
                () -> admission.admit(demand(full), context()));
        assertTrue(later.getCause() == headFailure.get().getCause(), "admission stays closed on the defect until the process is fixed");
    }

    @Test
    void twoEntriesOnOneKey_areNormalizedAndJointlyChecked() throws Exception {
        Meter bucket = new Meter("bucket", 1_000, 0, clock::get);
        bucket.drain();
        bucket.give(List.of(500));
        Demand twice = new Demand();
        twice.add(bucket, 300);
        twice.add(bucket, 300);
        assertEquals(1, twice.getEntries().size(), "two amounts on one account are one entry");
        assertEquals(List.of(300, 300), twice.getEntries().get(0).getAmounts());
        AtomicReference<Grant> out = new AtomicReference<>();
        admitAsync(twice, context(), out, new AtomicReference<>());
        assertTrue(await(() -> admission.queueSize() == 1, 5_000), "500 does not fit 600 checked jointly");

        bucket.give(List.of(100));
        assertTrue(await(() -> out.get() != null, 5_000));
        assertEquals(0, (int) bucket.level(), "both amounts were taken together");
    }

    @Test
    void aCancelledParkedJob_leavesAtOnce_andACancelledHeadStopsReserving() throws Exception {
        Slots slot = new Slots("slot", 1);
        Grant holder = admission.admit(demand(slot), context());
        JobContext<String> head = context();
        AtomicReference<Throwable> headOutcome = new AtomicReference<>();
        Thread headThread = admitAsync(demand(slot), head, new AtomicReference<>(), headOutcome);
        assertTrue(await(() -> admission.queueSize() == 1, 5_000));
        AtomicReference<Grant> follower = new AtomicReference<>();
        admitAsync(demand(slot), context(), follower, new AtomicReference<>());
        assertTrue(await(() -> admission.queueSize() == 2, 5_000));

        TestDoors.cancel(head, "test");
        headThread.join(5_000);

        assertTrue(headOutcome.get() instanceof CancellationException, "the parked job ends at once with the dispatcher's cancellation type: " + headOutcome.get());
        assertTrue(await(() -> admission.queueSize() == 1, 5_000), "the cancelled head has left the queue");
        admission.release(holder);
        assertTrue(await(() -> follower.get() != null, 5_000), "the follower is the head now and takes the permit");
    }

    @Test
    void stop_refusesEveryParkedWaiter_andAdmitAfterStopRefuses() throws Exception {
        Slots slot = new Slots("slot", 1);
        admission.admit(demand(slot), context());
        List<AtomicReference<Throwable>> outcomes = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            AtomicReference<Throwable> outcome = new AtomicReference<>();
            outcomes.add(outcome);
            threads.add(admitAsync(demand(slot), context(), new AtomicReference<>(), outcome));
        }
        assertTrue(await(() -> admission.queueSize() == 3, 5_000));

        admission.stop();
        for (Thread thread : threads) {
            thread.join(5_000);
        }

        assertTrue(outcomes.stream().allMatch(o -> o.get() instanceof CancellationException), "every parked waiter is cancelled at once");
        assertEquals(0, admission.queueSize());
        assertThrows(CancellationException.class, () -> admission.admit(demand(slot), context()));
    }

    /** A unit gate whose take can be made to block on the evaluator, so a grant can be timed against an interrupt. */
    private static final class BlockingTake extends CountingGate {
        final AtomicReference<CountDownLatch> block = new AtomicReference<>();
        final CountDownLatch inside = new CountDownLatch(1);

        BlockingTake() {
            super(1);
        }

        @Override
        public boolean tryTake(List<Void> mine, List<Void> reservedAhead) {
            CountDownLatch latch = block.get();
            if (latch != null) {
                inside.countDown();
                try {
                    latch.await();
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return super.tryTake(mine, reservedAhead);
        }

        @Override
        public String limiterName() {
            return "blocking";
        }
    }

    @Test
    void anInterruptThatRacesAGrant_rollsTheGrantBack() throws Exception {
        BlockingTake slot = new BlockingTake();
        Grant holder = admission.admit(demand(slot), context());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Grant> out = new AtomicReference<>();
        Thread waiter = admitAsync(demand(slot), context(), out, failure);
        assertTrue(await(() -> admission.queueSize() == 1, 5_000));
        CountDownLatch gate = new CountDownLatch(1);
        slot.block.set(gate);
        admission.release(holder);
        assertTrue(slot.inside.await(5, TimeUnit.SECONDS), "the evaluator is inside the take, holding the monitor, about to grant");

        waiter.interrupt();
        assertTrue(await(() -> {
            Object blocker = LockSupport.getBlocker(waiter);
            return blocker != null && blocker != admission;
        }, 5_000), "the interrupted waiter has left its park and is waiting for the monitor's lock to withdraw");
        gate.countDown();
        waiter.join(5_000);

        assertTrue(failure.get() instanceof InterruptedException, "the interrupt wins: " + failure.get());
        assertNull(out.get(), "no grant is handed to an interrupted job");
        assertEquals(0, slot.currentInUse(), "the grant that landed concurrently was rolled back and the permit is free");
        assertEquals(0, admission.queueSize());
    }

    @Test
    void aSecondStart_isRefused() {
        assertThrows(IllegalStateException.class, () -> admission.start(), "one evaluator per monitor");
    }

    @Test
    void aSecondRelease_isANoOp() throws InterruptedException {
        Slots slots = new Slots("slots", 2);
        Grant grant = admission.admit(demand(slots), context());
        assertEquals(1, slots.currentInUse());

        admission.release(grant);
        admission.release(grant);
        admission.rollback(grant);

        assertEquals(0, slots.currentInUse(), "the permit came back exactly once");
    }

    @Test
    void randomShapes_neverOverdrawAnyAccount_andEveryWaiterIsGranted() throws Exception {
        Random random = new Random(20260905);
        List<Slots> gates = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            gates.add(new Slots("gate" + i, 1 + random.nextInt(4)));
        }
        List<Meter> meters = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            meters.add(new Meter("meter" + i, 1_000, 5_000_000, clock::get));
        }
        int jobs = 120;
        CountDownLatch done = new CountDownLatch(jobs);
        AtomicInteger granted = new AtomicInteger();
        AtomicInteger overdrawn = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < jobs; i++) {
            Demand demand = new Demand();
            for (Slots gate : gates) {
                if (random.nextBoolean()) {
                    demand.add(gate, null);
                }
            }
            for (Meter meter : meters) {
                if (random.nextBoolean()) {
                    demand.add(meter, 1 + random.nextInt(400));
                }
            }
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    Grant grant = admission.admit(demand, context());
                    granted.incrementAndGet();
                    for (Slots gate : gates) {
                        if (gate.currentInUse() > gate.capacity() || gate.currentInUse() < 0) {
                            overdrawn.incrementAndGet();
                        }
                    }
                    for (Meter meter : meters) {
                        if (meter.level() < 0) {
                            overdrawn.incrementAndGet();
                        }
                    }
                    Thread.sleep(1);
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
        Thread ticker = Thread.ofVirtual().start(() -> {
            while (done.getCount() > 0) {
                clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(20));
                for (Meter meter : meters) {
                    meter.wake();
                }
                try {
                    Thread.sleep(2);
                }
                catch (InterruptedException e) {
                    return;
                }
            }
        });

        assertTrue(done.await(60, TimeUnit.SECONDS), "every waiter is eventually granted; " + admission.queueSize() + " still parked");
        ticker.interrupt();
        assertEquals(jobs, granted.get());
        assertEquals(0, overdrawn.get(), "no account ever went past its capacity or below zero");
        for (Slots gate : gates) {
            assertEquals(0, gate.currentInUse(), gate.limiterName() + " returned every permit");
        }
    }

    @Test
    void lockHoldTimePerPass_underTenThousandWaiters() throws Exception {
        Meter bucket = new Meter("bucket", 10_000, 0, clock::get);
        bucket.drain();
        int waiters = 10_000;
        List<AtomicReference<Grant>> grants = new ArrayList<>();
        for (int i = 0; i < waiters; i++) {
            AtomicReference<Grant> out = new AtomicReference<>();
            grants.add(out);
            admitAsync(tokens(bucket, 1), context(), out, new AtomicReference<>());
        }
        assertTrue(await(() -> admission.queueSize() == waiters, 60_000), "ten thousand parked waiters");

        long start = System.nanoTime();
        bucket.give(List.of(1));
        // the queue is FIFO in arrival order, and arrival is the moment a waiter's thread
        // reaches the lock, which ten thousand virtual threads do in no particular order: which
        // waiter is the head is theirs to decide, so what this test can hold the monitor to is
        // one token, one grant, and the pass that finds it
        assertTrue(await(() -> grants.stream().anyMatch(g -> g.get() != null), 10_000), "one token grants the head");
        long passMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals(waiters - 1, admission.queueSize());
        assertEquals(1, grants.stream().filter(g -> g.get() != null).count(), "one token, one grant");
        log.info("Admission pass over {} waiters, one grant: {} ms (wake latency included)", waiters, passMs);
        assertTrue(passMs < 2_000, "a pass over ten thousand waiters completes in well under two seconds: " + passMs + " ms");
    }

    @Test
    void aWaiterThatFitsAloneButNotWithTheHead_isHeld_neverRefused_andGrantedAfterTheHead() throws Exception {
        Meter bucket = new Meter("bucket", 1_000, 0, clock::get);
        Slots other = new Slots("other", 1);
        Grant otherHolder = admission.admit(demand(other), context());
        AtomicReference<Grant> head = new AtomicReference<>();
        admitAsync(tokensAnd(bucket, 800, other), context(), head, new AtomicReference<>());
        assertTrue(await(() -> admission.queueSize() == 1, 5_000), "the head is short on the other gate, not on tokens");
        AtomicReference<Grant> follower = new AtomicReference<>();
        AtomicReference<Throwable> followerFailure = new AtomicReference<>();
        admitAsync(tokens(bucket, 500), context(), follower, followerFailure);
        assertTrue(await(() -> admission.queueSize() == 2, 5_000));

        assertNull(followerFailure.get(), "500 fits the cap alone, so the follower is held, never refused");
        assertNull(follower.get(), "800 reserved for the head plus 500 exceeds 1000, so the follower waits");

        admission.release(otherHolder);
        assertTrue(await(() -> head.get() != null, 5_000), "the head takes its tokens and the gate");
        clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
        bucket.give(List.of(800));
        assertTrue(await(() -> follower.get() != null, 5_000), "then the follower is granted");
    }

    @Test
    void aHeadShortElsewhere_keepsTheElasticWindowsProbe() throws Exception {
        ProbeWindow window = new ProbeWindow();
        UpstreamFailure failure = new UpstreamFailure(429, "svc", "HTTP 429", 0);
        for (int i = 0; i < 12; i++) {
            window.onRateLimitError(failure);
        }
        assertEquals(ElasticWindowRateLimiter.CircuitState.BLOCKED, window.getCircuitState());
        Thread.sleep(60);
        Slots other = new Slots("other", 1);
        Grant otherHolder = admission.admit(demand(other), context());
        AtomicReference<Grant> head = new AtomicReference<>();
        admitAsync(demand(window, other), context(), head, new AtomicReference<>());
        assertTrue(await(() -> admission.queueSize() == 1, 5_000), "the head is short on the other gate");
        AtomicReference<Throwable> followerFailure = new AtomicReference<>();
        Thread follower = admitAsync(demand(window), context(), new AtomicReference<>(), followerFailure);

        follower.join(5_000);

        assertTrue(followerFailure.get() instanceof UncorrectableRuntimeLLMException, "the probe is spoken for by the head: " + followerFailure.get());
        assertEquals(ElasticWindowRateLimiter.CircuitState.BLOCKED, window.getCircuitState(), "the follower did not take the probe");
        admission.release(otherHolder);
        assertTrue(await(() -> head.get() != null, 5_000));
        assertEquals(ElasticWindowRateLimiter.CircuitState.PROBING, window.getCircuitState(), "the head took the probe when its other key cleared");
    }

    /** A window whose cooldown is short enough to expire inside a test. */
    private static final class ProbeWindow extends ElasticWindowRateLimiter {
        @Override
        protected long getBaseWindowMs() {
            return 1_000;
        }

        @Override
        protected int getMaxRequests() {
            return 10;
        }

        @Override
        protected long getInitialCooldownMs() {
            return 20;
        }

        @Override
        protected long getMaxCooldownMs() {
            return 1_000;
        }

        @Override
        public String limiterName() {
            return "probe-window";
        }
    }
}
