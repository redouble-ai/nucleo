/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;

import ai.redouble.nucleo.harness.errors.http.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TokenBucketRateLimiter}'s adaptive throttle loop and
 * the {@link TokenBucketRateLimiter#statusIndicator} surface that feeds
 * {@code SystemHealthReporter}.
 *
 * <p>Covers the vendor-neutral 429 handling: every {@code record429} bumps
 * the coefficient by the same increment regardless of subtype, sustained
 * successes decay it, and the coefficient is clamped at both ends.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-15)
 */
public class TokenBucketRateLimiterTest {

    private TokenBucketRateLimiter limiter;

    @BeforeEach
    void setUp() {
        TokenBucketRateLimiter.Config config = new TokenBucketRateLimiter.Config(1000, 1_000_000);
        limiter = new TokenBucketRateLimiter("test-model", config);
    }

    @Test
    void freshLimiter_statusIndicatorIsNull() {
        assertNull(limiter.statusIndicator(),
                "a brand-new limiter at throttle 0 is nominal");
        assertEquals(0.0, limiter.getThrottleCoefficient(),
                "fresh throttle starts at zero");
    }

    @Test
    void record429_bumpsThrottleCoefficient() {
        limiter.record429();

        assertEquals(0.5, limiter.getThrottleCoefficient(), 0.0001,
                "one 429 bumps by THROTTLE_INCREMENT (0.5)");
    }

    @Test
    void record429_multipleBumpsAccumulate() {
        limiter.record429();
        limiter.record429();
        limiter.record429();

        assertEquals(1.5, limiter.getThrottleCoefficient(), 0.0001,
                "throttle accumulates linearly with each 429");
    }

    @Test
    void record429_clampsAtMaxThrottle() {
        // THROTTLE_INCREMENT=0.5, MAX_THROTTLE=9.0, so 20 bumps is well over the cap.
        for (int i = 0; i < 20; i++) {
            limiter.record429();
        }

        assertEquals(9.0, limiter.getThrottleCoefficient(), 0.0001,
                "throttle clamps at MAX_THROTTLE (9.0 = 10x slower refill)");
    }

    @Test
    void statusIndicator_showsThrottleMultiplier() {
        limiter.record429();

        String status = limiter.statusIndicator();
        assertNotNull(status, "throttled limiter reports a status");
        assertTrue(status.startsWith("throttle:"),
                "status indicator is tagged 'throttle:' so the reporter can parse it for tinting");
        assertTrue(status.contains("1.5"),
                "status shows effective multiplier (1.0 + 0.5 = 1.5x slower)");
    }

    @Test
    void statusIndicator_trackThrottleGrowth() {
        limiter.record429();
        limiter.record429();

        String status = limiter.statusIndicator();
        assertTrue(status.contains("2.0"),
                "two 429s produce throttle 1.0 => 2.0x display, got: " + status);
    }

    @Test
    void recordSuccess_decreasesThrottleAfterEnoughSuccesses() {
        // Drive the throttle up.
        limiter.record429();
        limiter.record429();
        double before = limiter.getThrottleCoefficient();

        // SUCCESSES_PER_DECREASE=3: one bucket of successes triggers one decrement
        // of size THROTTLE_DECREMENT=0.2.
        limiter.recordSuccess();
        limiter.recordSuccess();
        limiter.recordSuccess();

        double after = limiter.getThrottleCoefficient();
        assertTrue(after < before,
                "three successes trigger a decrement (was " + before + ", now " + after + ")");
        assertEquals(before - 0.2, after, 0.0001,
                "decrement is exactly THROTTLE_DECREMENT");
    }

    @Test
    void recordSuccess_doesNothingWhenThrottleAlreadyZero() {
        // Fresh limiter at throttle 0.
        limiter.recordSuccess();
        limiter.recordSuccess();
        limiter.recordSuccess();

        assertEquals(0.0, limiter.getThrottleCoefficient(),
                "successes on a healthy limiter are no-ops; throttle stays at 0");
        assertNull(limiter.statusIndicator(),
                "healthy limiter still reports nominal");
    }

    @Test
    void recordSuccess_isSilentUntilBucketFills() {
        limiter.record429();
        double throttled = limiter.getThrottleCoefficient();

        // One or two successes should not yet decrement - need SUCCESSES_PER_DECREASE.
        limiter.recordSuccess();
        assertEquals(throttled, limiter.getThrottleCoefficient(), 0.0001,
                "first success does not yet decay throttle");
        limiter.recordSuccess();
        assertEquals(throttled, limiter.getThrottleCoefficient(), 0.0001,
                "second success does not yet decay throttle");
    }

    @Test
    void limiterCategory_isTokenBucket() {
        assertEquals("token_bucket", limiter.limiterCategory(),
                "category label is what SystemHealthReporter uses to pick unit ('tok')");
    }

    @Test
    void limiterName_isModelName() {
        assertEquals("test-model", limiter.limiterName(),
                "limiter identifies itself by the model name supplied at construction");
    }

    @Test
    void capacity_isTokensPerMinute() {
        assertEquals(1_000_000, limiter.capacity(),
                "capacity reports the TPM budget, not the request slot budget");
    }

    @Test
    void onRateLimitError_isSameAsRecord429() {
        // onRateLimitError is the generic RateLimiter interface entry point
        // (used by the dispatcher); it must feed the same loop as record429.
        limiter.onRateLimitError(new UpstreamFailure(429, "test-service", "HTTP 429: slow down", 0));

        assertEquals(0.5, limiter.getThrottleCoefficient(), 0.0001,
                "onRateLimitError delegates to record429");
    }

    @Test
    void setConfig_preservesThrottleCoefficient() {
        // Adaptive state survives header-driven cap updates: a successful response reports
        // the caps on every call, and that must not reset the 429 feedback loop.
        limiter.record429();
        limiter.record429();
        double before = limiter.getThrottleCoefficient();

        limiter.setConfig(new TokenBucketRateLimiter.Config(500, 500_000));

        assertEquals(before, limiter.getThrottleCoefficient(), 0.0001,
                "throttle coefficient is preserved across setConfig");
    }

    @Test
    void setConfig_clampsAllowanceDownWhenCapShrinks() {
        // Fresh limiter starts with availableTokens == tpm. Shrinking the
        // cap must clamp the current allowance so it never exceeds capacity.
        limiter.setConfig(new TokenBucketRateLimiter.Config(100, 100_000));

        assertEquals(100_000, limiter.capacity(),
                "capacity reflects the new tpm");
        assertTrue(limiter.getStatus().availableTokens() <= 100_000,
                "available tokens are clamped down to the new cap");
    }

    @Test
    void setConfig_growsCapacityWhenCapIncreases() {
        // Starting from (1000, 1_000_000), bump to a higher ceiling. The cap
        // should update immediately; the refill loop fills up to it over time.
        limiter.setConfig(new TokenBucketRateLimiter.Config(10_000, 15_000_000));

        assertEquals(15_000_000, limiter.capacity(),
                "capacity reflects the higher tpm");
    }

    @Test
    void fiveMinutesWithoutBackpressure_recoverFasterPerSuccess() {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
        TokenBucketRateLimiter clocked = new TokenBucketRateLimiter("clocked", new TokenBucketRateLimiter.Config(1000, 1_000_000), clock::get);
        clocked.record429();
        clocked.record429();
        assertEquals(1.0, clocked.getThrottleCoefficient(), 0.0001);
        clock.addAndGet(java.util.concurrent.TimeUnit.MINUTES.toNanos(5));
        clocked.recordSuccess();
        assertEquals(0.5, clocked.getThrottleCoefficient(), 0.0001,
                "five minutes without a 429: one success relaxes by two and a half decrements instead of one per three successes");
    }

    @Test
    void aRefundNeverRaisesTheLevelAboveTheCap() {
        limiter.give(java.util.List.of(5_000_000));
        assertEquals(1_000_000, limiter.getStatus().availableTokens(), "a refund is bounded by the token budget");
        assertEquals(1000, limiter.getStatus().availableRequests(), "and by the request budget");
    }

    @Test
    void moreRequestsThanTheMinuteAllows_isRefusedOutright() {
        TokenBucketRateLimiter two = new TokenBucketRateLimiter("two", new TokenBucketRateLimiter.Config(2, 1_000_000));
        ai.redouble.nucleo.harness.errors.UncorrectableRuntimeLLMException refusal =
                assertThrows(ai.redouble.nucleo.harness.errors.UncorrectableRuntimeLLMException.class,
                        () -> two.fits(java.util.List.of(1, 1, 1), java.util.List.of()));
        assertTrue(refusal.getMessage().contains("requests exceeds"), "the request dimension refuses what can never fit: " + refusal.getMessage());
    }

    @Test
    void setConfig_isASilentNoOpWhenValuesMatch() {
        // The provider reports the same caps on every call; an unchanged pair must neither log
        // an update nor wake the evaluator.
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(TokenBucketRateLimiter.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            java.util.concurrent.atomic.AtomicInteger wakes = new java.util.concurrent.atomic.AtomicInteger();
            limiter.onCapacityChange(wakes::incrementAndGet);
            limiter.record429();
            double before = limiter.getThrottleCoefficient();

            limiter.setConfig(new TokenBucketRateLimiter.Config(1000, 1_000_000));

            assertEquals(1_000_000, limiter.capacity(), "capacity unchanged after no-op update");
            assertEquals(before, limiter.getThrottleCoefficient(), 0.0001, "adaptive state unchanged after no-op update");
            assertEquals(0, wakes.get(), "an unchanged pair wakes nobody");
            assertTrue(appender.list.stream().noneMatch(e -> e.getFormattedMessage().startsWith("Updated rate limits")), "and logs no update");

            limiter.setConfig(new TokenBucketRateLimiter.Config(2000, 1_000_000));

            assertEquals(1, wakes.get(), "a grown cap wakes the evaluator once");
            assertEquals(1, appender.list.stream().filter(e -> e.getFormattedMessage().startsWith("Updated rate limits for test-model")).count(), "and logs the update once");
        }
        finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void onRateLimitError_logsTheUpstreamsSummaryAtWarn() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(TokenBucketRateLimiter.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            UpstreamFailure failure = new UpstreamFailure(429, "test-service", "HTTP 429: slow down", 7);
            limiter.onRateLimitError(failure);
            assertEquals(1, appender.list.size(), "one line per upstream failure");
            assertEquals(ch.qos.logback.classic.Level.WARN, appender.list.get(0).getLevel());
            assertEquals("test-model upstream failure: " + failure.summary(), appender.list.get(0).getFormattedMessage(),
                    "the model and the upstream's summary are on record");
        }
        finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void getStatus_readsTheLevelFromTheClock() {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
        TokenBucketRateLimiter clocked = new TokenBucketRateLimiter("clocked", new TokenBucketRateLimiter.Config(60, 60_000), clock::get);
        assertTrue(clocked.tryTake(java.util.List.of(60_000), java.util.List.of()));
        assertEquals(0, clocked.getStatus().availableTokens(), "drained at this instant");
        assertEquals(59, clocked.getStatus().availableRequests());
        clock.addAndGet(java.util.concurrent.TimeUnit.SECONDS.toNanos(30));
        assertEquals(30_000, clocked.getStatus().availableTokens(), 1, "half a minute later the status shows half the budget refilled");
        assertEquals(60, clocked.getStatus().availableRequests(), "and the request slot is back");
    }
}
