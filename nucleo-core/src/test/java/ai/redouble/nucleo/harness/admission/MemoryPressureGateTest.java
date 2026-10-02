/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the "Memory Is a Resource Too" contract. The latch: set at 95%, cleared only below the 90%
 * recovery threshold, with a two-second re-check while set because garbage collection signals
 * nothing. The drain: while memory's line is non-empty, a completion or the cubic delay for the
 * heap as it reads now, whichever comes first, releases one; a completion releases at most one
 * and nothing accumulates; the delay floors at 50ms; a closed delay is reported as its instant,
 * never as null. With nobody in the line a job starts at once, at any heap below the latch.
 *
 * <p>The heap ratio, the held count and the clock are faked through the package-private seams,
 * so the thresholds and the intervals are exact rather than dependent on the test JVM.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-31)
 */
class MemoryPressureGateTest {

    private static final List<Void> NONE = Collections.emptyList();
    private static final long RECHECK_NANOS = MemoryPressureGate.CRITICAL_CHECK_INTERVAL_MS * 1_000_000L;

    /** Gate whose heap ratio, held count and clock are whatever the test says they are. */
    static final class FakeRatioGate extends MemoryPressureGate {
        volatile double ratio;
        private final AtomicLong now = new AtomicLong();
        volatile int held;

        FakeRatioGate(double ratio) {
            this.ratio = ratio;
            observeHeld(() -> held);
        }

        @Override
        double getHeapUsageRatio() {
            return ratio;
        }

        @Override
        long nanoTime() {
            return now.get();
        }

        /** The fake clock's reading. */
        long now() {
            return now.get();
        }

        void setNow(long nanos) {
            now.set(nanos);
        }

        void advance(long nanos) {
            now.addAndGet(nanos);
        }
    }

    private static long delayNanosAt(double ratio) {
        return MemoryPressureGate.calculateThrottleDelay(ratio).toNanos();
    }

    @Test
    void aLoneArrivalPassesAtOnceBelowTheLatch() {
        for (double ratio : new double[] {0.50, 0.80, 0.90, 0.94}) {
            FakeRatioGate gate = new FakeRatioGate(ratio);
            for (int i = 0; i < 5; i++) {
                assertTrue(gate.tryTake(NONE, NONE), "nobody in the line at " + ratio + ", grant " + i);
            }
            assertNull(gate.earliestFit(NONE, NONE), "nothing is pending at " + ratio);
        }
    }

    @Test
    void throttleDelayFollowsTheDocumentedCubicCurve() {
        assertEquals(50, MemoryPressureGate.calculateThrottleDelay(0.50).toMillis(), "the floor holds below the curve");
        assertEquals(50, MemoryPressureGate.calculateThrottleDelay(0.80).toMillis(), "floor where the curve begins");
        long at85 = MemoryPressureGate.calculateThrottleDelay(0.85).toMillis();
        long at90 = MemoryPressureGate.calculateThrottleDelay(0.90).toMillis();
        long at94 = MemoryPressureGate.calculateThrottleDelay(0.94).toMillis();
        assertTrue(at85 >= 1000 && at85 <= 1300, "~1.1s at 85% per the documented curve, got " + at85);
        assertTrue(at90 >= 8500 && at90 <= 9200, "~8.9s at 90% per the documented curve, got " + at90);
        assertTrue(at85 < at90 && at90 < at94, "the delay grows monotonically toward the critical boundary");
        assertEquals(MemoryPressureGate.MAX_THROTTLE_DELAY_MS, MemoryPressureGate.calculateThrottleDelay(0.95).toMillis(), "the ceiling at the boundary");
        assertEquals(MemoryPressureGate.MAX_THROTTLE_DELAY_MS, MemoryPressureGate.calculateThrottleDelay(0.99).toMillis(), "and the ceiling holds above it; a latched gate reports the ceiling, not a runaway cube");
    }

    @Test
    void theDelayReleasesOne_whenNothingFinishesFirst() {
        FakeRatioGate gate = new FakeRatioGate(0.90);
        gate.held = 3;
        long start = gate.now();
        assertTrue(gate.tryTake(NONE, NONE), "the first release goes at once");
        assertFalse(gate.fits(NONE, NONE), "the next one waits for the delay or a completion");
        assertEquals(Math.min(start + delayNanosAt(0.90), start + RECHECK_NANOS), gate.earliestFit(NONE, NONE),
                "admission is told when to look again: the delay, or the re-check if that comes first");
        gate.setNow(start + delayNanosAt(0.90) - 1);
        assertFalse(gate.fits(NONE, NONE));
        gate.setNow(start + delayNanosAt(0.90));
        assertTrue(gate.fits(NONE, NONE));
        assertTrue(gate.tryTake(NONE, NONE), "one more, and the delay starts again");
        assertFalse(gate.fits(NONE, NONE));
    }

    @Test
    void aCompletionReleasesOne_withoutWaitingOutTheDelay() {
        FakeRatioGate gate = new FakeRatioGate(0.94);
        gate.held = 3;
        assertTrue(gate.tryTake(NONE, NONE));
        assertFalse(gate.fits(NONE, NONE), "the 94% delay is twenty-four seconds");
        gate.give(NONE);
        assertTrue(gate.fits(NONE, NONE), "a job finished, so the next may go now");
        assertTrue(gate.tryTake(NONE, NONE));
        assertFalse(gate.fits(NONE, NONE), "one completion, one release; the delay runs again from here");
    }

    @Test
    void completionsDoNotAccumulate() {
        FakeRatioGate gate = new FakeRatioGate(0.94);
        gate.held = 5;
        assertTrue(gate.tryTake(NONE, NONE));
        gate.give(NONE);
        gate.give(NONE);
        gate.give(NONE);
        assertTrue(gate.tryTake(NONE, NONE), "three completions let one through");
        assertFalse(gate.fits(NONE, NONE), "and not a second: there are no seats to count");
    }

    @Test
    void whicheverComesFirst() {
        FakeRatioGate gate = new FakeRatioGate(0.85);
        gate.held = 4;
        long start = gate.now();
        assertTrue(gate.tryTake(NONE, NONE));
        gate.setNow(start + delayNanosAt(0.85) / 2);
        gate.give(NONE);
        assertTrue(gate.tryTake(NONE, NONE), "the completion came first");
        long second = gate.now();
        gate.setNow(second + delayNanosAt(0.85));
        assertTrue(gate.tryTake(NONE, NONE), "the delay came first");
    }

    // ---------------------------------------------------------------- the growth penalty

    /** One look at the given reading: what the evaluator does on every wake. */
    private static void look(FakeRatioGate gate, double ratio) {
        gate.ratio = ratio;
        gate.fits(NONE, NONE);
    }

    /** A collection that left the heap at the given reading: a look above it, then one at it, which makes it the floor. */
    private static void collectionTo(FakeRatioGate gate, double ratio) {
        look(gate, ratio + 0.01);
        look(gate, ratio);
    }

    /** Releases one at each of the given floors, each set by a collection, with the line non-empty and the clock far enough along. */
    private static FakeRatioGate afterReleasesAtFloors(double... floors) {
        FakeRatioGate gate = new FakeRatioGate(floors[0]);
        gate.held = 10;
        for (double floor : floors) {
            collectionTo(gate, floor);
            release(gate);
        }
        return gate;
    }

    private static void release(FakeRatioGate gate) {
        gate.advance(TimeUnit.HOURS.toNanos(2));
        assertTrue(gate.tryTake(NONE, NONE), "release at floor " + gate.ratio);
    }

    @Test
    void theFloorIsTheReadingAfterAFall_andRisesNeverMoveIt() {
        FakeRatioGate gate = new FakeRatioGate(0.50);
        look(gate, 0.50);
        assertEquals(0.50, gate.getStatus().floorRatio(), 0.0001, "the first reading is the floor");
        look(gate, 0.70);
        assertEquals(0.50, gate.getStatus().floorRatio(), 0.0001, "a rise is garbage, the floor stays");
        look(gate, 0.60);
        assertEquals(0.60, gate.getStatus().floorRatio(), 0.0001, "a fall is a collection, the reading after it is the floor");
        look(gate, 0.65);
        assertEquals(0.60, gate.getStatus().floorRatio(), 0.0001);
    }

    @Test
    void noPenaltyBeforeTwoReleases_orWithoutNetGrowth() {
        FakeRatioGate gate = afterReleasesAtFloors(0.80);
        collectionTo(gate, 0.92);
        assertEquals(1.0, gate.growthPenalty(), "one release is not a climb");
        gate = afterReleasesAtFloors(0.89, 0.92, 0.89);
        collectionTo(gate, 0.92);
        assertEquals(1.0, gate.growthPenalty(), "89, 92, 89, 92 with nothing behind it is no growth");
        assertEquals(delayNanosAt(0.92), gate.penalizedDelay(0.92).toNanos(), "so the curve alone applies");
    }

    @Test
    void aClimbOfTheFloor_compoundsThePenalty() {
        FakeRatioGate gate = afterReleasesAtFloors(0.80, 0.83);
        collectionTo(gate, 0.86);
        assertEquals(64.0, gate.growthPenalty(), 0.001, "six points over the last two releases");
        assertEquals(64 * MemoryPressureGate.calculateThrottleDelay(0.86).toMillis(), gate.penalizedDelay(0.86).toMillis(), "two minutes at 86% instead of two seconds");
        release(gate);
        collectionTo(gate, 0.89);
        assertEquals(4096.0, gate.growthPenalty(), 0.001, "the climb went on under the sixty-four, so it compounds");
    }

    @Test
    void aLiveSetHeldWhereItIs_keepsThePenaltyThatHoldsIt() {
        FakeRatioGate gate = afterReleasesAtFloors(0.80, 0.83, 0.86, 0.89, 0.86);
        collectionTo(gate, 0.89);
        assertEquals(4096.0, gate.growthPenalty(), 0.001, "86, 89, 86, 89 after the climb: the penalty is working, it stays");
        release(gate);
        collectionTo(gate, 0.86);
        assertEquals(4096.0, gate.growthPenalty(), 0.001, "and stays on the way back down to 86");
    }

    @Test
    void aJumpInOneRelease_isAStop_cappedAtAnHour() {
        FakeRatioGate gate = afterReleasesAtFloors(0.80, 0.80);
        collectionTo(gate, 0.92);
        assertEquals(4096.0, gate.growthPenalty(), 0.001, "twelve points since the release before last");
        assertEquals(MemoryPressureGate.MAX_PENALIZED_DELAY_MS, gate.penalizedDelay(0.92).toMillis(), "seventeen hours capped at one");
    }

    @Test
    void garbageBetweenCollections_raisesTheReadingAndNotThePenalty() {
        FakeRatioGate gate = afterReleasesAtFloors(0.80, 0.83);
        assertEquals(8.0, gate.growthPenalty(), 0.001, "three points of floor over the last two releases");
        look(gate, 0.86);
        look(gate, 0.90);
        look(gate, 0.94);
        assertEquals(0.83, gate.getStatus().floorRatio(), 0.0001, "eleven points of garbage since the collection: the floor is unmoved");
        assertEquals(8.0, gate.growthPenalty(), 0.001, "and so is the penalty");
        assertEquals(8 * MemoryPressureGate.calculateThrottleDelay(0.94).toMillis(), gate.penalizedDelay(0.94).toMillis(), "the curve reads the level, the penalty reads the floor");
        collectionTo(gate, 0.84);
        assertEquals(16.0, gate.growthPenalty(), 0.001, "the collection shows one more point of live set, and that is priced");
    }

    @Test
    void aFallOfTheFloorDividesThePenalty_andAnEmptiedHeapClearsIt() {
        FakeRatioGate gate = afterReleasesAtFloors(0.80, 0.83, 0.86);
        gate.held = 10;
        assertFalse(gate.fits(NONE, NONE), "at 86% with a climb behind it, the next waits");
        collectionTo(gate, 0.82);
        assertEquals(32.0, gate.growthPenalty(), 0.001, "one point below the floor of the release before last halves the sixty-four");
        collectionTo(gate, 0.20);
        assertEquals(1.0, gate.growthPenalty(), "sixty-three points down is a reset");
        gate.advance(delayNanosAt(0.20));
        assertTrue(gate.fits(NONE, NONE), "so the wait is the 50ms floor");
    }

    @Test
    void thePenaltyIsCapped_soSeventeenPointsDownFromAnywhereIsAReset() {
        FakeRatioGate gate = afterReleasesAtFloors(0.80, 0.80, 0.92, 0.92);
        assertEquals(MemoryPressureGate.MAX_GROWTH_PENALTY, gate.growthPenalty(), 0.001, "four thousand compounded by four thousand stops at the ceiling");
        assertEquals(MemoryPressureGate.MAX_PENALIZED_DELAY_MS, gate.penalizedDelay(0.92).toMillis());
        collectionTo(gate, 0.75);
        assertEquals(1.0, gate.growthPenalty(), 0.001, "seventeen points down from the ceiling is a reset");
    }

    @Test
    void memorysLineBelowTheCurve_stillDrainsOneAtATime() {
        FakeRatioGate gate = new FakeRatioGate(0.50);
        gate.held = 25;
        long start = gate.now();
        assertTrue(gate.tryTake(NONE, NONE), "the head goes at once");
        assertFalse(gate.fits(NONE, NONE), "half the heap free is not a reason to release twenty-four more together");
        assertEquals(start + delayNanosAt(0.50), gate.earliestFit(NONE, NONE), "spaced at the 50ms floor");
        gate.setNow(start + delayNanosAt(0.50));
        assertTrue(gate.tryTake(NONE, NONE));
    }

    @Test
    void theEvaluatorIsToldToLookAgainWithinTheRecheck() {
        FakeRatioGate gate = new FakeRatioGate(0.94);
        gate.held = 3;
        assertTrue(gate.tryTake(NONE, NONE));
        assertTrue(delayNanosAt(0.94) > RECHECK_NANOS, "the 94% delay is far longer than the re-check");
        assertEquals(gate.now() + RECHECK_NANOS, gate.earliestFit(NONE, NONE), "so the deadline is the re-check, not the delay");
        gate.ratio = 0.79;
        assertEquals(gate.now() + delayNanosAt(0.79), gate.earliestFit(NONE, NONE),
                "a collection that dropped the heap below the curve shrinks the wait to the 50ms floor, not to zero");
        gate.advance(delayNanosAt(0.79));
        assertTrue(gate.fits(NONE, NONE));
    }

    @Test
    void theDelayIsTheHeapNow_notTheHeapAtTheLastRelease() {
        FakeRatioGate gate = new FakeRatioGate(0.90);
        gate.held = 3;
        assertTrue(gate.tryTake(NONE, NONE));
        gate.advance(delayNanosAt(0.85));
        assertFalse(gate.fits(NONE, NONE), "at 90% the 85% delay is not enough");
        gate.ratio = 0.85;
        assertTrue(gate.fits(NONE, NONE), "the same elapsed time is enough once the heap reads 85%");
    }

    @Test
    void aClosedDelayIsReportedAsItsInstant_neverAsNull() {
        FakeRatioGate gate = new FakeRatioGate(0.90);
        gate.held = 3;
        long start = gate.now();
        assertTrue(gate.tryTake(NONE, NONE));
        assertFalse(gate.fits(NONE, NONE));
        gate.setNow(start + delayNanosAt(0.90) + 1);
        assertTrue(gate.fits(NONE, NONE), "the delay has elapsed");
        assertEquals(start + delayNanosAt(0.90), gate.earliestFit(NONE, NONE),
                "a past instant means now; null would park the evaluator until an event that may never come");
    }

    @Test
    void theLatchClearsIntoTheDrain_notIntoAFlood() {
        FakeRatioGate gate = new FakeRatioGate(0.96);
        gate.held = 5;
        assertFalse(gate.tryTake(NONE, NONE), "latched, nothing is granted");
        gate.ratio = 0.89;
        assertTrue(gate.tryTake(NONE, NONE), "the latch clears and one job is released");
        assertFalse(gate.tryTake(NONE, NONE), "the second waits for the 89% delay or a completion");
        assertTrue(gate.earliestFit(NONE, NONE) > gate.now());
    }

    @Test
    void aCompletionUnderTheLatch_releasesNobody() {
        FakeRatioGate gate = new FakeRatioGate(0.96);
        gate.held = 5;
        gate.give(NONE);
        assertFalse(gate.fits(NONE, NONE), "critical is critical, whatever finished");
        gate.ratio = 0.89;
        assertTrue(gate.tryTake(NONE, NONE), "the latch clears and one goes");
        assertFalse(gate.tryTake(NONE, NONE), "the completion that landed under the latch was spent by that release; the next waits");
    }

    @Test
    void criticalLatchSetsAtNinetyFive_andClearsOnlyBelowNinety() {
        FakeRatioGate gate = new FakeRatioGate(0.96);
        assertFalse(gate.fits(NONE, NONE), "at 96% the latch sets and nothing is granted");
        assertEquals(gate.now() + RECHECK_NANOS, gate.earliestFit(NONE, NONE), "while latched the evaluator re-checks on the clock");
        assertEquals("blocked", gate.statusIndicator());
        gate.ratio = 0.93;
        assertFalse(gate.fits(NONE, NONE), "hysteresis: 93% is below critical but above recovery, the latch stays");
        gate.ratio = 0.89;
        assertTrue(gate.fits(NONE, NONE), "below the recovery threshold the latch clears");
        assertNull(gate.earliestFit(NONE, NONE), "and with no release yet there is nothing to wait for");
        assertTrue(gate.tryTake(NONE, NONE), "a grant attempt reads the same latch");
    }

    @Test
    void theLatchIsLoggedWhenItSets_andWhenItClears() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(MemoryPressureGate.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            FakeRatioGate gate = new FakeRatioGate(0.96);
            gate.fits(NONE, NONE);
            assertEquals(1, appender.list.size(), "one line when the latch sets");
            assertEquals(ch.qos.logback.classic.Level.WARN, appender.list.get(0).getLevel(), "setting is a warning");
            assertTrue(appender.list.get(0).getFormattedMessage().startsWith("Memory latch set: "), appender.list.get(0).getFormattedMessage());
            gate.ratio = 0.93;
            gate.fits(NONE, NONE);
            assertEquals(1, appender.list.size(), "nothing logged while the latch holds");
            gate.ratio = 0.89;
            gate.fits(NONE, NONE);
            assertEquals(2, appender.list.size(), "one line when it clears");
            assertEquals(ch.qos.logback.classic.Level.INFO, appender.list.get(1).getLevel(), "clearing is information");
            assertTrue(appender.list.get(1).getFormattedMessage().startsWith("Memory latch cleared: "), appender.list.get(1).getFormattedMessage());
        }
        finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void stoppingGateWavesEveryoneThrough() {
        FakeRatioGate gate = new FakeRatioGate(0.99);
        gate.held = 10;
        gate.stop();
        assertTrue(gate.fits(NONE, NONE), "during shutdown the gate never strands anyone");
        assertTrue(gate.tryTake(NONE, NONE));
    }

    @Test
    void statusReportsTheZone() {
        assertEquals("memory", new FakeRatioGate(0.50).limiterCategory(), "the heap account's category");
        assertEquals("memory", new FakeRatioGate(0.50).limiterName());
        assertEquals("GREEN", new FakeRatioGate(0.50).getStatus().zone());
        FakeRatioGate draining = new FakeRatioGate(0.90);
        draining.held = 3;
        assertTrue(draining.tryTake(NONE, NONE));
        MemoryPressureStatus status = draining.getStatus();
        assertEquals("DRAINING", status.zone());
        assertEquals(3, status.heldOnMemory());
        assertEquals(delayNanosAt(0.90) / 1_000_000L, status.pacingDelayMillis());
        assertTrue(draining.statusIndicator().startsWith("pace:"));
        draining.give(NONE);
        assertEquals(0, draining.getStatus().pacingDelayMillis(), "a completion means the next release is due now");
        FakeRatioGate critical = new FakeRatioGate(0.97);
        assertFalse(critical.fits(NONE, NONE));
        assertEquals("CRITICAL", critical.getStatus().zone());
        assertEquals("blocked", critical.statusIndicator());
    }
}
