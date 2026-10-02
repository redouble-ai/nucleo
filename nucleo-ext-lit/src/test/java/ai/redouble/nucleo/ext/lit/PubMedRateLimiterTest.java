/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.lit;

import ai.redouble.nucleo.harness.errors.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCBI's ten-per-second ceiling, expressed as an admission account.
 *
 * <p>Ten requests fit a one-second window and the eleventh waits for the window to turn rather
 * than being refused, because time alone closes that shortfall. A tool books one slot per job
 * and admission holds it for the job's duration, so PubMedSearchTool's sequential ESearch and
 * EFetch calls ride one held slot and the account admits ten concurrent searches; the window
 * arithmetic must also honor a demand of several units at once, and a test that only proved
 * "some limit exists" would not catch a change that broke that arithmetic.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-06)
 */
class PubMedRateLimiterTest {
    private static final List<Void> NONE = Collections.emptyList();

    private static List<Void> units(int count) {
        return Collections.nCopies(count, null);
    }

    @Test
    void tenRequestsFitOneSecondAndTheEleventhDoesNot() {
        PubMedRateLimiter limiter = new PubMedRateLimiter();
        assertTrue(limiter.tryTake(units(10), NONE), "NCBI's published ceiling with an API key is ten a second");
        assertFalse(limiter.fits(units(1), NONE), "the eleventh request in the same window does not fit");
    }

    @Test
    void aTwoUnitDemandCountsAsTwoSoFiveTakesFillTheWindow() {
        PubMedRateLimiter limiter = new PubMedRateLimiter();
        for (int take = 1; take <= 5; take++) {
            assertTrue(limiter.tryTake(units(2), NONE),
                    "take " + take + " admits its two units together");
        }
        assertFalse(limiter.fits(units(2), NONE),
                "a sixth two-unit take does not fit, because five takes already spent all ten slots");
    }

    @Test
    void anOverfullWindowWaitsForTheClockRatherThanBeingRefused() {
        PubMedRateLimiter limiter = new PubMedRateLimiter();
        assertTrue(limiter.tryTake(units(10), NONE));
        Long fitAt = limiter.earliestFit(units(1), NONE);
        assertNotNull(fitAt, "a full window is a shortfall the clock closes, so the account names when");
        long waitMs = (fitAt - System.nanoTime()) / 1_000_000L;
        assertTrue(waitMs <= 1_000, "the wait is bounded by the one-second window, got " + waitMs + "ms");
    }

    @Test
    void aDemandPastTheWholeWindowIsRefusedOutright() {
        PubMedRateLimiter limiter = new PubMedRateLimiter();
        assertThrows(UncorrectableRuntimeLLMException.class, () -> limiter.fits(units(11), NONE),
                "eleven at once exceeds the window's whole capacity, so no amount of waiting helps "
                        + "and the account refuses instead of parking the job forever");
    }
}
