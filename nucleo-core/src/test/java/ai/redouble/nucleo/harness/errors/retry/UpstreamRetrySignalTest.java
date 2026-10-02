/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors.retry;

import ai.redouble.nucleo.harness.admission.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the {@link UpstreamRetryException} contract: every transparent retry signal
 * carries its own pacing and its own fleet-throttle weight, and the limiter applies
 * the weight it is handed. The dispatcher's single retry loop and the shared
 * throttle both read these values, so a drift here silently changes how the whole
 * fleet behaves under provider pressure.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-25)
 */
public class UpstreamRetrySignalTest {

    @Test
    void eachSignalCarriesItsOwnPacingAndWeight() {
        RateLimitRetryException rateLimit = new RateLimitRetryException("429", null, 0, null, null, null);
        OverloadRetryException overload = new OverloadRetryException("529", "model", "overloaded", null);
        TransientErrorRetryException fault = new TransientErrorRetryException("500", "model", "api_error", 0, 0, null);

        assertEquals(1, rateLimit.backpressureIncrements(),
                "a 429 is the caller's own budget - one increment converges");
        assertEquals(3, overload.backpressureIncrements(),
                "a 529 is the provider's whole fleet drowning while every concurrent job hears"
                        + " the same thing - yield hard now, successes walk it back");
        assertEquals(0, fault.backpressureIncrements(),
                "a plain 5xx is a fault, not a slow-down request - throttling the fleet over"
                        + " a stray 500 would be an overreaction");
        assertTrue(overload.baseMinJitterMs() > rateLimit.baseMinJitterMs()
                        && overload.baseMaxJitterMs() > rateLimit.baseMaxJitterMs(),
                "overload episodes last minutes while their refusals fast-fail in seconds, so"
                        + " the 529 wait window is wider than the 429 one");
        assertEquals(rateLimit.baseMinJitterMs(), fault.baseMinJitterMs());
        assertEquals(rateLimit.baseMaxJitterMs(), fault.baseMaxJitterMs());
    }

    @Test
    void limiterAppliesTheWeightItIsHanded() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter("probe-model",
                new TokenBucketRateLimiter.Config(60, 60000));

        limiter.record429();
        double afterOne = limiter.getThrottleCoefficient();
        limiter.recordBackpressure(3);
        double afterFour = limiter.getThrottleCoefficient();

        assertTrue(afterOne > 0.0, "one increment moves the coefficient");
        assertEquals(afterOne * 4, afterFour, 1e-9,
                "a weighted signal is exactly its weight in single increments - record429 IS"
                        + " recordBackpressure(1), one loop, no parallel bookkeeping");
    }

    @Test
    void theThrottleIsCapped() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter("probe-model",
                new TokenBucketRateLimiter.Config(60, 60000));

        limiter.recordBackpressure(1000);
        double ceiling = limiter.getThrottleCoefficient();
        limiter.recordBackpressure(3);

        assertEquals(ceiling, limiter.getThrottleCoefficient(), 1e-9,
                "a storm of overload signals saturates at the cap instead of growing without bound");
    }
}
