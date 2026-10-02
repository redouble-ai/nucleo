/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.ratelimiters;

import ai.redouble.nucleo.ext.patent.epo.*;
import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.errors.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The EPO router as an admission account with no identity of its own: every question is routed
 * to the bucket of the service it names, reservations ahead apply per bucket, a take that fails
 * on one bucket gives back what it took on the others, and the wake fans out to every bucket.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
class EPORateLimiterTest {

    private static final List<EPOService> NONE = Collections.emptyList();

    private static List<EPOService> times(EPOService service, int count) {
        return Collections.nCopies(count, service);
    }

    @Test
    void eachServiceIsItsOwnAccount() {
        EPORateLimiter router = new EPORateLimiter();
        LimiterIdentity search = router.accountFor(EPOService.SEARCH);
        LimiterIdentity retrieval = router.accountFor(EPOService.RETRIEVAL);
        assertNotSame(search, retrieval);
        assertEquals("epo:search", search.limiterName());
        assertEquals("epo:retrieval", retrieval.limiterName());

        assertTrue(router.tryTake(times(EPOService.SEARCH, 6), NONE), "search holds six per minute");
        assertFalse(router.fits(times(EPOService.SEARCH, 1), NONE), "the seventh search does not fit");
        assertTrue(router.fits(times(EPOService.RETRIEVAL, 1), NONE), "retrieval is untouched by search pressure");
        assertEquals(6, search.currentInUse());
        assertEquals(0, retrieval.currentInUse());
    }

    @Test
    void aReservationAheadAppliesOnlyToTheBucketItNames() {
        EPORateLimiter router = new EPORateLimiter();
        assertTrue(router.tryTake(times(EPOService.SEARCH, 5), NONE));
        assertTrue(router.fits(times(EPOService.SEARCH, 1), NONE), "one search slot left fits a lone asker");
        assertFalse(router.fits(times(EPOService.SEARCH, 1), times(EPOService.SEARCH, 1)), "with the head's search reserved, a follower's search does not fit");
        assertTrue(router.fits(times(EPOService.RETRIEVAL, 1), times(EPOService.SEARCH, 1)), "a head reserved on search does not hold a follower on retrieval");
        assertNull(router.earliestFit(times(EPOService.SEARCH, 1), times(EPOService.SEARCH, 6)), "search plus a full reservation can never fit the window");
    }

    @Test
    void aTakeThatFailsOnOneBucket_givesBackTheOthers() {
        EPORateLimiter router = new EPORateLimiter();
        assertTrue(router.tryTake(times(EPOService.SEARCH, 6), NONE), "search is now full");
        List<EPOService> mixed = List.of(EPOService.RETRIEVAL, EPOService.RETRIEVAL, EPOService.SEARCH);

        assertFalse(router.tryTake(mixed, NONE), "search cannot fit, so the whole take declines");

        assertEquals(0, router.accountFor(EPOService.RETRIEVAL).currentInUse(), "the two retrieval slots taken before the failure were given back");
        assertEquals(6, router.accountFor(EPOService.SEARCH).currentInUse());
    }

    @Test
    void giveReturnsToTheBucketsNamed() {
        EPORateLimiter router = new EPORateLimiter();
        assertTrue(router.tryTake(List.of(EPOService.IMAGES, EPOService.IMAGES, EPOService.OTHER), NONE));
        router.give(List.of(EPOService.IMAGES, EPOService.OTHER));
        assertEquals(1, router.accountFor(EPOService.IMAGES).currentInUse());
        assertEquals(0, router.accountFor(EPOService.OTHER).currentInUse());
    }

    @Test
    void anImpossibleDemandOnOneBucket_isRefused() {
        EPORateLimiter router = new EPORateLimiter();
        assertThrows(UncorrectableRuntimeLLMException.class, () -> router.fits(times(EPOService.SEARCH, 7), NONE),
                "seven searches can never fit a window of six");
    }

    @Test
    void theWakeFansOutToEveryBucket() {
        EPORateLimiter router = new EPORateLimiter();
        AtomicInteger wakes = new AtomicInteger();
        router.onCapacityChange(wakes::incrementAndGet);
        router.onRateLimitError(new ai.redouble.nucleo.harness.errors.http.UpstreamFailure(429, "epo", "HTTP 429", 0), EPOService.INPADOC);
        for (int i = 0; i < 3; i++) {
            router.onSuccess(EPOService.INPADOC);
        }
        assertEquals(1, wakes.get(), "the inpadoc bucket's throttle relaxation reached the monitor's wake");
    }
}
