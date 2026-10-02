/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;

import ai.redouble.nucleo.harness.admission.AdmissionFixtures.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.http.*;
import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The parts of the account contract every family shares: {@link AbstractRateLimiter} is its own
 * identity, answers {@code RELEASE}, reports a nominal indicator, runs the installed wake on a
 * capacity change and forgets it when null is installed; the {@link RateLimiter} defaults route
 * the per-input feedback to the plain calls, need no HTTP gate and ignore the line; a
 * {@link CountingGate} stamps its last activity, reports its status and refuses on the take what
 * it refuses on the check; {@link LimiterEvents} sums numeric amounts and counts the rest and
 * never throws; and every status record's summary names its numbers.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class AccountContractTest {

    private static final List<Void> NONE = Collections.emptyList();

    /** A unit gate of two, for the counting-gate rows. */
    static final class Pair extends CountingGate {
        Pair() {
            super(2);
        }

        @Override
        public String limiterName() {
            return "pair";
        }
    }

    /** An account that counts the plain feedback calls, so the per-input defaults can be seen delegating. */
    static final class Counting extends AbstractRateLimiter<Integer> {
        final AtomicInteger successes = new AtomicInteger();
        final AtomicReference<UpstreamFailure> lastFailure = new AtomicReference<>();

        @Override
        public boolean fits(List<Integer> mine, List<Integer> reservedAhead) {
            return true;
        }

        @Override
        public boolean tryTake(List<Integer> mine, List<Integer> reservedAhead) {
            return true;
        }

        @Override
        public void give(List<Integer> amounts) {
        }

        @Override
        public Long earliestFit(List<Integer> mine, List<Integer> reservedAhead) {
            return null;
        }

        @Override
        public String limiterName() {
            return "counting";
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
            return 0;
        }

        @Override
        public void onSuccess() {
            successes.incrementAndGet();
        }

        @Override
        public void onRateLimitError(UpstreamFailure failure) {
            lastFailure.set(failure);
        }

        @Override
        public RateLimiterStatus getStatus() {
            return () -> "counting";
        }
    }

    @Test
    void theBaseAccountIsItsOwnIdentity_releasesOnClose_andIsNominalByDefault() {
        Pair gate = new Pair();
        assertSame(gate, gate.accountFor(null), "a plain account is the identity its amounts debit");
        assertEquals(RateLimiter.Replenishment.RELEASE, gate.replenishment(), "a capacity gate's permit comes back with the work");
        assertNull(gate.statusIndicator(), "nothing abnormal to report");
    }

    @Test
    void theInstalledWakeRunsOnACapacityChange_andNullClearsIt() {
        Scripted account = new Scripted("scripted");
        AtomicInteger wakes = new AtomicInteger();
        account.onCapacityChange(wakes::incrementAndGet);
        account.wakeAll();
        assertEquals(1, wakes.get(), "a capacity change runs the one installed wake");
        account.onCapacityChange(null);
        account.wakeAll();
        assertEquals(1, wakes.get(), "null clears the slot");
    }

    @Test
    void thePerInputFeedbackDelegatesToThePlainCalls_andTheOtherDefaultsHold() {
        Counting account = new Counting();
        account.onSuccess(42);
        assertEquals(1, account.successes.get(), "onSuccess(input) reaches onSuccess()");
        UpstreamFailure failure = new UpstreamFailure(429, "svc", "HTTP 429", 0);
        account.onRateLimitError(failure, 42);
        assertSame(failure, account.lastFailure.get(), "onRateLimitError(failure, input) reaches onRateLimitError(failure)");
        assertFalse(account.requiresHttpConnection(), "an account needs no HTTP gate unless it says so");
        account.observeHeld(() -> {
            throw new AssertionError("the default observeHeld never consults the line it is handed");
        });
        assertTrue(account.fits(List.of(1), List.of()), "an account that does not override observeHeld is unchanged by it");
        assertTrue(account.tryTake(List.of(1), List.of()));
    }

    @Test
    void aCountingGateStampsItsLastActivity_andReportsItsStatus() throws InterruptedException {
        Pair gate = new Pair();
        Instant born = gate.lastActivity();
        Thread.sleep(2);
        assertTrue(gate.tryTake(Collections.singletonList(null), NONE));
        Instant taken = gate.lastActivity();
        assertTrue(taken.isAfter(born), "a take stamps the activity");
        SemaphoreLimiterStatus status = gate.getStatus();
        assertEquals(2, status.maxConcurrent());
        assertEquals(1, status.availablePermits());
        assertEquals(1, status.activeCount());
        assertEquals(taken, status.lastActivity(), "the status carries the stamp");
        assertTrue(status.summary().contains("1/2"), "the summary names active over maximum: " + status.summary());
        Thread.sleep(2);
        gate.give(Collections.singletonList(null));
        assertTrue(gate.lastActivity().isAfter(taken), "a give stamps it too");
    }

    @Test
    void aCountingGateRefusesADemandBeyondItsCapacityOnTheTakeToo() {
        Pair gate = new Pair();
        List<Void> three = Collections.nCopies(3, null);
        UncorrectableRuntimeLLMException refusal = assertThrows(UncorrectableRuntimeLLMException.class, () -> gate.tryTake(three, NONE));
        assertTrue(refusal.getMessage().contains("can never fit"), refusal.getMessage());
        assertEquals(0, gate.currentInUse(), "nothing was taken");
    }

    @Test
    void amountsAreSummedWhenNumeric_andCountedOtherwise() {
        assertEquals(600, LimiterEvents.amountOf(List.of(300, 300)), "tokens sum");
        assertEquals(3, LimiterEvents.amountOf(Collections.nCopies(3, null)), "unit permits count one each");
        assertEquals(0, LimiterEvents.amountOf(List.of()));
    }

    /** An account whose identity cannot even be read, so building its event fails. */
    static final class Unreadable extends CountingGate {
        Unreadable() {
            super(1);
        }

        @Override
        public String limiterName() {
            throw new IllegalStateException("no name for you");
        }
    }

    @Test
    void emissionIsBestEffort_withNoBusItIsSilent_andWithOneAFailureIsSwallowed() {
        assertDoesNotThrow(() -> LimiterEvents.emit(null, new Unreadable(), null, 0, ai.redouble.nucleo.events.LimiterEvent.Type.HELD, 0, null, 1),
                "with no bus nothing is built and nothing is thrown");
        ai.redouble.nucleo.harness.MessageBus bus = new ai.redouble.nucleo.harness.LinkedQueueMessageBus();
        assertDoesNotThrow(() -> LimiterEvents.emit(bus, new Unreadable(), null, 0, ai.redouble.nucleo.events.LimiterEvent.Type.HELD, 0, null, 1),
                "with a bus, an event that cannot be built is dropped and admission never hears of it");
    }

    @Test
    void everyStatusSummaryNamesItsNumbers() {
        TokenBucketStatus bucket = new TokenBucketStatus(10, 3, 1_000, 250, Instant.EPOCH);
        assertTrue(bucket.summary().contains("3/10") && bucket.summary().contains("250/1000"), bucket.summary());
        assertTrue(bucket.hasCapacity(250), "room for a request of exactly what is left");
        assertFalse(bucket.hasCapacity(251), "one token more than is left");
        assertFalse(new TokenBucketStatus(10, 0, 1_000, 999, Instant.EPOCH).hasCapacity(1), "no request slot, no capacity");
        ElasticWindowStatus window = new ElasticWindowStatus(5, 2, 1.0, 2_000, "HEALTHY");
        assertTrue(window.summary().contains("2/5") && window.summary().contains("2000ms") && window.summary().contains("HEALTHY"), window.summary());
        MemoryPressureStatus memory = new MemoryPressureStatus(0, 1, 0.5, 0.4, "DRAINING", 3, 120, 8.0);
        assertTrue(memory.summary().contains("zone DRAINING") && memory.summary().contains("3 in memory's line") && memory.summary().contains("120ms"), memory.summary());
    }
}
