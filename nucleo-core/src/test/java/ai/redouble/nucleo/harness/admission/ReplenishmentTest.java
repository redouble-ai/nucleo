/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;
import static ai.redouble.nucleo.harness.admission.AdmissionFixtures.*;

/**
 * Covers {@link RateLimiter.Replenishment}: the classification of each limiter family, and the
 * property the classification exists to protect - that a TIME account's window still holds after
 * the work that took a slot has finished.
 *
 * <p>Returning a TIME account's permit at completion would make throughput
 * {@code permits / job duration} instead of {@code permits / window} and make throttling inert:
 * widening the window only slows the time-based refill, which would no longer be the path
 * handing permits back.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-17)
 */
class ReplenishmentTest {

    private static final List<Void> NONE = Collections.emptyList();

    /** Two permits per second, so the third take in a window must wait for the clock. */
    private static final class TinyWindow extends ElasticWindowRateLimiter {
        @Override protected long getBaseWindowMs() {return 1_000;}
        @Override protected int getMaxRequests() {return 2;}
        @Override public String limiterName() {return "test:tiny";}
    }

    private static List<Void> one() {
        return Collections.singletonList(null);
    }

    @Test
    void timeBasedLimitersDoNotReplenishOnRelease() {
        assertEquals(RateLimiter.Replenishment.TIME, new TinyWindow().replenishment());
        assertEquals(RateLimiter.Replenishment.TIME,
                new TokenBucketRateLimiter("m", new TokenBucketRateLimiter.Config(10, 1000)).replenishment());
    }

    @Test
    void concurrencyLimitersReplenishOnRelease() {
        // Heap, connections and subprocess slots are genuinely free once the job ends.
        assertEquals(RateLimiter.Replenishment.RELEASE, MemoryPressureGate.getInstance().replenishment());
        assertEquals(RateLimiter.Replenishment.RELEASE, new DatabaseGate("d", 1).replenishment());
    }

    @Test
    void windowStillBindsAfterTheWorkFinishes() {
        // A fast job handing its slot straight back would let a 2/sec window admit as many
        // requests as jobs complete. Two takes fill the window; the third does not fit, and only
        // the clock can change that.
        TinyWindow limiter = new TinyWindow();
        assertTrue(limiter.tryTake(one(), NONE));
        assertTrue(limiter.tryTake(one(), NONE));
        assertFalse(limiter.fits(one(), NONE), "window of 2 must be exhausted");

        Long deadline = limiter.earliestFit(one(), NONE);
        assertNotNull(deadline, "a metered shortfall has a clock deadline");
        long waitMs = (deadline - System.nanoTime()) / 1_000_000L;
        assertTrue(waitMs > 800 && waitMs <= 1_000,
                "the third take is due when the oldest stamp leaves the ~1s window, " + waitMs + "ms away");
    }

    @Test
    void giveStillRefundsForRollback() {
        // give() remains the rollback path: the grant was taken, a later account failed, the
        // upstream call never happened, so the slot is genuinely unspent.
        TinyWindow limiter = new TinyWindow();
        assertTrue(limiter.tryTake(one(), NONE));
        assertTrue(limiter.tryTake(one(), NONE));
        assertFalse(limiter.fits(one(), NONE));
        limiter.give(one());
        assertTrue(limiter.fits(one(), NONE), "a rolled-back slot must be reusable immediately");
    }

    @Test
    void releaseReturnsOnlyReleaseKeys_rollbackReturnsEverything() throws InterruptedException {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        Admission admission = new Admission(new MemoryFake(), clock::get, (a, s, w, t, wait, r, amt) -> { });
        admission.start();
        try {
            Slots slot = new Slots("slot", 1);
            Meter meter = new Meter("meter", 1_000, 0, clock::get);
            Demand demand = new Demand();
            demand.add(slot, null);
            demand.add(meter, 400);

            Grant done = admission.admit(demand, context());
            admission.release(done);
            assertEquals(0, slot.currentInUse(), "a held slot is free once the job ends");
            assertEquals(600, (int) meter.level(), "a TIME debit is spent against the window; returning it would make throughput a function of job duration");

            Grant abandoned = admission.admit(demand, context());
            admission.rollback(abandoned);
            assertEquals(0, slot.currentInUse());
            assertEquals(600, (int) meter.level(), "rollback refunds the metered debit whose call never happened");
            assertEquals(1, meter.gives.get(), "one refund for the rollback, none for the release");
        }
        finally {
            admission.stop();
        }
    }
}
