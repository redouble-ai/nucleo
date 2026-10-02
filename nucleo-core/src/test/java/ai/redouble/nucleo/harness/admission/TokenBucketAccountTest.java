/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;

import ai.redouble.nucleo.harness.errors.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the admission half of {@link TokenBucketRateLimiter} as a clocked account: a drained
 * bucket does not fit, its deadline is the deficit over the rate, the clock alone makes it fit,
 * a refund is immediate, throttle stretches the deadline, an impossible amount is refused, a cap
 * lowered under a parked amount refuses it at the next check, and the debit is atomic against a
 * clamp landing between the check and the take. The throttle-coefficient half lives in
 * {@link TokenBucketRateLimiterTest}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-31)
 */
public class TokenBucketAccountTest {

    private static final List<Integer> NONE = List.of();

    private final AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(1));

    private TokenBucketRateLimiter bucket(int rpm, int tpm) {
        return new TokenBucketRateLimiter("account-test", new TokenBucketRateLimiter.Config(rpm, tpm), clock::get);
    }

    @Test
    void drainedBucketDoesNotFit_andItsDeadlineIsTheDeficitOverTheRate() {
        TokenBucketRateLimiter limiter = bucket(600, 6_000);
        assertTrue(limiter.tryTake(List.of(6_000), NONE), "a full bucket admits up to its capacity at once");
        assertFalse(limiter.fits(List.of(100), NONE), "the drained bucket does not fit");

        Long deadline = limiter.earliestFit(List.of(100), NONE);

        assertNotNull(deadline);
        long waitMs = TimeUnit.NANOSECONDS.toMillis(deadline - clock.get());
        assertEquals(1_000, waitMs, 1, "100 tokens at 6000 per minute is one second away");
    }

    @Test
    void theClockAloneMakesItFit() {
        TokenBucketRateLimiter limiter = bucket(600, 6_000);
        limiter.tryTake(List.of(6_000), NONE);
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(999));
        assertFalse(limiter.fits(List.of(100), NONE), "one millisecond short");
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(2));
        assertTrue(limiter.fits(List.of(100), NONE), "the refill landed");
        assertTrue(limiter.tryTake(List.of(100), NONE));
    }

    @Test
    void aRefundReturnsUnspentTokensImmediately() {
        TokenBucketRateLimiter limiter = bucket(600, 6_000);
        assertTrue(limiter.tryTake(List.of(6_000), NONE));
        assertFalse(limiter.fits(List.of(1_000), NONE));
        limiter.give(List.of(6_000));
        assertTrue(limiter.fits(List.of(1_000), NONE),
                "a reservation whose call never happened is genuinely unspent and reusable now");
    }

    @Test
    void throttleStretchesTheDeadline() {
        TokenBucketRateLimiter limiter = bucket(600, 6_000);
        limiter.tryTake(List.of(6_000), NONE);
        long plain = limiter.earliestFit(List.of(100), NONE) - clock.get();
        limiter.record429();
        limiter.record429();
        long throttled = limiter.earliestFit(List.of(100), NONE) - clock.get();
        assertEquals(2.0, (double) throttled / plain, 0.01, "throttle 1.0 doubles the effective window");
    }

    @Test
    void reservationAheadOfTheHead_isFalseNeverRefused_whenTheUnionExceedsTheCap() {
        TokenBucketRateLimiter limiter = bucket(600, 1_000);
        assertFalse(limiter.fits(List.of(500), List.of(800)), "500 fits alone; with 800 reserved it does not");
        assertNull(limiter.earliestFit(List.of(500), List.of(800)), "no clock instant satisfies the union; the head's grant is the event");
        assertTrue(limiter.fits(List.of(500), List.of(400)), "with 400 reserved the union fits the cap");
    }

    @Test
    void anAmountAboveTheCap_isRefusedOutright() {
        TokenBucketRateLimiter limiter = bucket(600, 1_000);
        UncorrectableRuntimeLLMException refusal = assertThrows(UncorrectableRuntimeLLMException.class,
                () -> limiter.fits(List.of(1_001), NONE));
        assertTrue(refusal.getMessage().contains("exceeds"), refusal.getMessage());
        assertThrows(UncorrectableRuntimeLLMException.class, () -> limiter.tryTake(List.of(1_001), NONE));
    }

    @Test
    void aCapLoweredUnderAParkedAmount_refusesItAtTheNextCheck() {
        TokenBucketRateLimiter limiter = bucket(600, 10_000);
        limiter.tryTake(List.of(10_000), NONE);
        assertFalse(limiter.fits(List.of(9_000), NONE), "parked: the bucket is drained");

        limiter.setConfig(new TokenBucketRateLimiter.Config(600, 8_000));

        assertThrows(UncorrectableRuntimeLLMException.class, () -> limiter.fits(List.of(9_000), NONE),
                "9000 can never fit a cap of 8000: the next pass refuses the head instead of blocking the queue forever");
    }

    @Test
    void tryTakeAfterAConcurrentClamp_returnsFalse() {
        TokenBucketRateLimiter limiter = bucket(600, 10_000);
        assertTrue(limiter.fits(List.of(9_000), NONE), "the pre-check passes on the full bucket");

        limiter.setConfig(new TokenBucketRateLimiter.Config(600, 9_500));
        limiter.tryTake(List.of(1_000), NONE);

        assertFalse(limiter.tryTake(List.of(9_000), NONE), "the debit re-checks under the account lock and declines");
        assertTrue(limiter.getStatus().availableTokens() <= 9_500);
    }

    @Test
    void requestsAreTheSecondDimension() {
        TokenBucketRateLimiter limiter = bucket(2, 1_000_000);
        assertTrue(limiter.tryTake(List.of(1), NONE));
        assertTrue(limiter.tryTake(List.of(1), NONE));
        assertFalse(limiter.fits(List.of(1), NONE), "two requests per minute are spent");
        Long deadline = limiter.earliestFit(List.of(1), NONE);
        assertNotNull(deadline);
        assertEquals(30_000, TimeUnit.NANOSECONDS.toMillis(deadline - clock.get()), 1, "one request refills in half a minute");
    }
}
