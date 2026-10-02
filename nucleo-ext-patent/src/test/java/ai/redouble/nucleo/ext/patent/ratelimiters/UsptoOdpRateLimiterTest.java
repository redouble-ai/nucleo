/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.ratelimiters;

import ai.redouble.nucleo.harness.errors.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The ceiling the USPTO Open Data Portal imposes, expressed as an admission account.
 *
 * <p>ODP allows one request in flight per API key, so this account holds exactly one slot per
 * window and a second asker does not fit until the window turns. That is the whole point of
 * the limiter: without it, two ODP tools admitted at once would both call and one would be
 * rejected upstream. The window is 250ms, which puts sustained throughput at about four
 * requests a second and therefore under ODP's documented floor rather than at it.
 *
 * <p>A demand of two can never be granted, however long anyone waits, so it is refused outright
 * rather than parked - the account contract's rule for an amount that exceeds capacity.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-06)
 */
class UsptoOdpRateLimiterTest {
    private static final List<Void> NONE = Collections.emptyList();

    private static List<Void> units(int count) {
        return Collections.nCopies(count, null);
    }

    @Test
    void oneRequestIsInFlightAtATimeBecauseOdpAllowsExactlyOne() {
        UsptoOdpRateLimiter limiter = new UsptoOdpRateLimiter();
        assertTrue(limiter.fits(units(1), NONE), "a lone caller fits the single slot");
        assertTrue(limiter.tryTake(units(1), NONE), "and takes it");
        assertFalse(limiter.fits(units(1), NONE),
                "the slot is spent, so a second concurrent ODP call does not fit - which is the "
                        + "burst=1 ceiling the API enforces on its side");
    }

    @Test
    void aSecondCallerWaitsForTheWindowRatherThanBeingRefused() {
        UsptoOdpRateLimiter limiter = new UsptoOdpRateLimiter();
        assertTrue(limiter.tryTake(units(1), NONE));
        Long fitAt = limiter.earliestFit(units(1), NONE);
        assertNotNull(fitAt,
                "one request per window is a shortfall the clock closes, so the account names when "
                        + "rather than refusing");
        long waitMs = (fitAt - System.nanoTime()) / 1_000_000L;
        assertTrue(waitMs <= 250,
                "the wait is bounded by the 250ms window, which is what puts throughput near four a "
                        + "second, got " + waitMs + "ms");
    }

    @Test
    void aDemandOfTwoIsRefusedOutrightBecauseItCanNeverFit() {
        UsptoOdpRateLimiter limiter = new UsptoOdpRateLimiter();
        assertThrows(UncorrectableRuntimeLLMException.class, () -> limiter.fits(units(2), NONE),
                "two concurrent ODP requests exceed the account's whole capacity, so waiting could "
                        + "never help and the account says so instead of parking the job forever");
    }

    @Test
    void theHeadsReservationHoldsTheOnlySlot() {
        UsptoOdpRateLimiter limiter = new UsptoOdpRateLimiter();
        assertTrue(limiter.fits(units(1), NONE), "with nothing reserved ahead, the slot is available");
        assertFalse(limiter.fits(units(1), units(1)),
                "with the queue head's request reserved, a follower does not take the slot from under it");
    }

    @Test
    void theAccountNamesItselfSoAdmissionAndMonitoringCanAttributeIt() {
        assertEquals("uspto-odp", new UsptoOdpRateLimiter().limiterName(),
                "the name is what limiter events and the health snapshot attribute ODP pressure to");
    }
}
