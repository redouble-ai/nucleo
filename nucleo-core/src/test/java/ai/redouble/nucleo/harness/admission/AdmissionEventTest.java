/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.events.LimiterEvent.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;
import static ai.redouble.nucleo.harness.admission.AdmissionFixtures.*;

/**
 * The {@link ai.redouble.nucleo.events.LimiterEvent} contract {@link Admission} owns:
 * the shapes every dashboard and metric integration reads, pinned per account.
 *
 * <ul>
 *   <li>Fast path emits GRANTED_IMMEDIATE and nothing else.</li>
 *   <li>Slow path emits HELD then GRANTED_FROM_HOLD, with post-transition waiter counts.</li>
 *   <li>Interrupt while parked emits REJECTED "cancelled"; a refusal emits REJECTED
 *       "circuit_blocked"; an account bug emits REJECTED "aborted" and the waiter's exception
 *       carries the bug as cause; stop emits REJECTED "shutdown".</li>
 *   <li>release and rollback emit RELEASED.</li>
 *   <li>N slow-path waiters produce exactly 2N transitions on the account and balanced counts.</li>
 *   <li>Every event carries the waiting job's snapshot.</li>
 * </ul>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
class AdmissionEventTest {

    private final List<LimiterEvent> events = new CopyOnWriteArrayList<>();
    private final MemoryFake memory = new MemoryFake();
    private Admission admission;

    @BeforeEach
    void start() {
        admission = new Admission(memory, System::nanoTime, this::record);
        admission.start();
    }

    @AfterEach
    void stop() {
        admission.stop();
    }

    private void record(LimiterIdentity account, JobSnapshot snapshot, int waiters, Type type, long waitNanos, String reason, long amount) {
        events.add(new LimiterEvent(snapshot, Instant.now(), account.limiterName(), account.limiterCategory(),
                account.capacity(), account.currentInUse(), waiters, type, waitNanos, reason, account.statusIndicator(), amount));
    }

    private List<LimiterEvent> on(String account) {
        return events.stream().filter(e -> e.limiterName().equals(account)).toList();
    }

    private List<LimiterEvent> on(String account, Type type) {
        return events.stream().filter(e -> e.limiterName().equals(account) && e.type() == type).toList();
    }

    @Test
    void fastPath_emitsSingleGrantedImmediate() throws InterruptedException {
        Slots slot = new Slots("slot", 1);

        Grant grant = admission.admit(demand(slot), context());

        assertNotNull(grant);
        assertEquals(1, on("slot").size());
        assertEquals(Type.GRANTED_IMMEDIATE, on("slot").get(0).type());
        assertEquals(0, on("slot").get(0).waiters(), "fast path does not increment waiters");
        assertNotNull(on("slot").get(0).snapshot(), "every event carries the job's snapshot");
    }

    @Test
    void slowPath_emitsHeldThenGrantedFromHold_withPostTransitionWaiterCounts() throws Exception {
        Slots slot = new Slots("slot", 1);
        Grant first = admission.admit(demand(slot), context());
        events.clear();
        AtomicReference<Grant> second = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread waiter = Thread.ofVirtual().start(() -> {
            try {
                second.set(admission.admit(demand(slot), context()));
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            finally {
                done.countDown();
            }
        });

        assertTrue(await(() -> !on("slot", Type.HELD).isEmpty(), 5_000), "the second job parks and is HELD on the slot");
        assertEquals(1, on("slot", Type.HELD).size(), "HELD is emitted exactly once per admission per account");
        assertEquals(1, on("slot", Type.HELD).get(0).waiters(), "HELD shows the parked job as a waiter");

        admission.release(first);
        assertTrue(done.await(5, TimeUnit.SECONDS));
        waiter.join(5_000);

        assertNotNull(second.get());
        LimiterEvent granted = on("slot", Type.GRANTED_FROM_HOLD).get(0);
        assertEquals(0, granted.waiters(), "GRANTED_FROM_HOLD carries the post-transition waiter count");
        assertTrue(granted.waitNanos() > 0, "wait duration is captured on GRANTED_FROM_HOLD");
        assertEquals(1, on("slot", Type.RELEASED).size(), "release emits RELEASED");
        assertEquals(0, admission.queueSize());
        assertTrue(events.stream().allMatch(e -> e.snapshot() != null), "every event of the run carries the job's snapshot");
    }

    @Test
    void stop_emitsRejectedShutdownOnEveryAccountAWaiterWasHeldOn() throws Exception {
        Slots slot = new Slots("slot", 1);
        Grant first = admission.admit(demand(slot), context());
        events.clear();
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread waiter = Thread.ofVirtual().start(() -> {
            try {
                admission.admit(demand(slot), context());
            }
            catch (Throwable t) {
                outcome.set(t);
            }
        });
        assertTrue(await(() -> !on("slot", Type.HELD).isEmpty(), 5_000));

        admission.stop();
        waiter.join(5_000);

        assertTrue(outcome.get() instanceof CancellationException, "the parked waiter is cancelled: " + outcome.get());
        LimiterEvent rejected = on("slot", Type.REJECTED).get(0);
        assertEquals("shutdown", rejected.rejectReason());
        assertEquals(0, rejected.waiters(), "the count is balanced after the drain");
        assertNotNull(rejected.snapshot());
        admission.release(first);
    }

    @Test
    void interruptWhileParked_emitsRejectedCancelled() throws Exception {
        Slots slot = new Slots("slot", 1);
        Grant first = admission.admit(demand(slot), context());
        events.clear();
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread waiter = Thread.ofVirtual().start(() -> {
            try {
                admission.admit(demand(slot), context());
            }
            catch (Throwable t) {
                outcome.set(t);
            }
        });
        assertTrue(await(() -> !on("slot", Type.HELD).isEmpty(), 5_000));

        waiter.interrupt();
        waiter.join(5_000);

        assertTrue(outcome.get() instanceof InterruptedException, "interrupt surfaces as InterruptedException: " + outcome.get());
        LimiterEvent rejected = on("slot", Type.REJECTED).get(0);
        assertEquals("cancelled", rejected.rejectReason());
        assertEquals(0, rejected.waiters(), "waiter count balanced after the withdrawal");
        assertEquals(0, admission.queueSize());
        admission.release(first);
    }

    @Test
    void refusal_emitsRejectedCircuitBlocked_andReachesTheCaller() {
        Scripted circuit = new Scripted("circuit");
        circuit.mode = Scripted.Mode.REFUSE;

        UncorrectableRuntimeLLMException refusal = assertThrows(UncorrectableRuntimeLLMException.class,
                () -> admission.admit(demand(circuit), context()));

        assertTrue(refusal.getMessage().contains("pick a different tool"), "the account's own message reaches the LLM: " + refusal.getMessage());
        LimiterEvent rejected = on("circuit", Type.REJECTED).get(0);
        assertEquals("circuit_blocked", rejected.rejectReason());
        assertEquals(0, rejected.waitNanos(), "refused before ever being held");
    }

    @Test
    void accountBug_emitsRejectedAborted_andTheWaiterCarriesTheCause() {
        Scripted broken = new Scripted("broken");
        broken.mode = Scripted.Mode.BROKEN;

        UncorrectableRuntimeLLMException failure = assertThrows(UncorrectableRuntimeLLMException.class,
                () -> admission.admit(demand(broken), context()));

        assertTrue(failure.getCause() instanceof IllegalStateException, "the bug travels as the cause: " + failure.getCause());
        assertEquals("aborted", on("broken", Type.REJECTED).get(0).rejectReason());
    }

    @Test
    void rollback_emitsReleasedOnEveryAccount() throws InterruptedException {
        Slots slot = new Slots("slot", 2);
        Grant grant = admission.admit(demand(slot), context());
        events.clear();

        admission.rollback(grant);

        assertEquals(1, on("slot", Type.RELEASED).size());
        assertEquals(0, slot.currentInUse());
    }

    @Test
    void threeSlowPathWaiters_produceExactlySixTransitions_andBalancedCounts() throws Exception {
        Slots slot = new Slots("slot", 1);
        Grant holder = admission.admit(demand(slot), context());
        events.clear();
        List<Thread> waiters = new CopyOnWriteArrayList<>();
        List<Grant> grants = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 3; i++) {
            waiters.add(Thread.ofVirtual().start(() -> {
                try {
                    grants.add(admission.admit(demand(slot), context()));
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        assertTrue(await(() -> on("slot", Type.HELD).size() == 3, 5_000), "three parked waiters, three HELD");
        assertEquals(3, on("slot", Type.HELD).get(2).waiters(), "the third HELD shows three waiters");

        admission.release(holder);
        for (int i = 0; i < 3; i++) {
            int expected = i + 1;
            assertTrue(await(() -> grants.size() == expected, 5_000), "waiter " + expected + " granted in turn");
            admission.release(grants.get(i));
        }
        for (Thread waiter : waiters) {
            waiter.join(5_000);
        }

        assertEquals(3, on("slot", Type.HELD).size());
        assertEquals(3, on("slot", Type.GRANTED_FROM_HOLD).size(), "one GRANTED_FROM_HOLD per HELD");
        List<LimiterEvent> granted = on("slot", Type.GRANTED_FROM_HOLD);
        assertEquals(0, granted.get(2).waiters(), "the last grant leaves nobody waiting");
        assertEquals(0, admission.queueSize());
    }

    @Test
    void memoryLatched_holdsEveryoneOnTheMemoryKey_untilItClears() throws Exception {
        Slots slot = new Slots("slot", 5);
        memory.clear = false;
        AtomicReference<Grant> outcome = new AtomicReference<>();
        Thread waiter = Thread.ofVirtual().start(() -> {
            try {
                outcome.set(admission.admit(demand(slot), context()));
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(await(() -> !on("memory", Type.HELD).isEmpty(), 5_000), "a job is HELD on memory while the latch is set");
        assertTrue(on("slot").isEmpty(), "no account is consulted while memory is critical");

        memory.clear = true;
        memory.wake();
        waiter.join(5_000);

        assertNotNull(outcome.get(), "the job is granted once memory clears");
        assertEquals(1, on("memory", Type.GRANTED_FROM_HOLD).size(), "memory reports the grant it delayed");
        assertSame(outcome.get().getDemand().getEntries().get(0).getKey(), slot);
    }
}
