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
 * Unit tests for {@link ElasticWindowRateLimiter#statusIndicator()}. The
 * indicator is what {@code SystemHealthReporter} uses to color a row red
 * (blocked) or yellow (throttled / probing) - so these tests lock down the
 * state transitions that drive the visual alarm.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-15)
 */
public class ElasticWindowStatusIndicatorTest {

    private static final UpstreamFailure FAILURE =
            new UpstreamFailure(503, "test-service", "HTTP 503 at /fetch: unavailable", 0);

    @Test
    void freshLimiter_statusIsNull() {
        TestLimiter limiter = TestLimiter.create(10, 1000);
        assertNull(limiter.statusIndicator(),
                "HEALTHY circuit + zero throttle => nominal, no indicator");
    }

    @Test
    void afterRateLimitError_statusShowsThrottleMultiplier() {
        TestLimiter limiter = TestLimiter.create(10, 1000);
        limiter.onRateLimitError(FAILURE);

        String status = limiter.statusIndicator();
        assertNotNull(status, "onRateLimitError must produce a visible indicator");
        assertTrue(status.startsWith("throttle:"),
                "throttle events get the 'throttle:' prefix so the reporter tints yellow");
    }

    @Test
    void sustainedRateLimitErrors_tripCircuitToBlocked() {
        // Drive the coefficient to MAX_THROTTLE so failuresAtMaxToBlock starts counting.
        TestLimiter limiter = TestLimiter.create(10, 1000);
        int maxThrottleBumps = (int) Math.ceil(limiter.publicMaxThrottle() / 0.5) + 1;
        for (int i = 0; i < maxThrottleBumps; i++) {
            limiter.onRateLimitError(FAILURE);
        }
        // Once at max, subsequent failures must trip the circuit breaker.
        int failuresAtMax = limiter.publicFailuresAtMaxToBlock();
        for (int i = 0; i < failuresAtMax + 1; i++) {
            limiter.onRateLimitError(FAILURE);
        }

        assertEquals("blocked", limiter.statusIndicator(),
                "circuit opens after sustained failures at max throttle");
    }

    @Test
    void categoryIsElasticWindow() {
        TestLimiter limiter = TestLimiter.create(10, 1000);
        assertEquals("elastic_window", limiter.limiterCategory());
    }

    /**
     * Minimal concrete subclass. Parent constructor reads
     * {@link TestLimiter#getBaseWindowMs} / {@link TestLimiter#getMaxRequests} before subclass
     * fields initialise, so we smuggle them in via a ThreadLocal.
     */
    private static final ThreadLocal<Integer> configMaxRequests = new ThreadLocal<>();
    private static final ThreadLocal<Long> configWindowMs = new ThreadLocal<>();

    private static class TestLimiter extends ElasticWindowRateLimiter {
        private final int maxRequests;
        private final long baseWindowMs;

        private TestLimiter() {
            super();
            this.maxRequests = configMaxRequests.get();
            this.baseWindowMs = configWindowMs.get();
        }

        static TestLimiter create(int maxRequests, long baseWindowMs) {
            configMaxRequests.set(maxRequests);
            configWindowMs.set(baseWindowMs);
            try {
                return new TestLimiter();
            } finally {
                configMaxRequests.remove();
                configWindowMs.remove();
            }
        }

        @Override
        protected long getBaseWindowMs() {
            Long tl = configWindowMs.get();
            return tl != null ? tl : baseWindowMs;
        }

        @Override
        protected int getMaxRequests() {
            Integer tl = configMaxRequests.get();
            return tl != null ? tl : maxRequests;
        }

        double publicMaxThrottle() {
            return getMaxThrottle();
        }

        int publicFailuresAtMaxToBlock() {
            return getFailuresAtMaxToBlock();
        }
    }
}
