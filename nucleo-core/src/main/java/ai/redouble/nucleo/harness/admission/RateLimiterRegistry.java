/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;

import ai.redouble.nucleo.harness.models.*;

/**
 * Lookup facade for per-model accounts: the {@link TokenBucketRateLimiter} of an entry
 * bounded by a quota window, the {@link ModelGate} of an entry bounded by a concurrency.
 * Shared across all clients of a given model so global limits are honored even when many
 * jobs use the same model concurrently. Delegates to {@link RateLimiterFactory} for the
 * actual creation and lifecycle.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-08-12)
 */
public class RateLimiterRegistry {

    private static final RateLimiterRegistry INSTANCE = new RateLimiterRegistry();

    private RateLimiterRegistry() {
        // Private constructor for singleton
    }

    /**
     * Gets the singleton instance.
     */
    public static RateLimiterRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Gets or creates a rate limiter for the given model.
     *
     * @param model the model
     * @return the rate limiter for this model
     */
    public TokenBucketRateLimiter getRateLimiter(ModelSpec model) {
        // Delegate to factory for centralized management
        return RateLimiterFactory.getInstance().getRateLimiter(model);
    }
    /**
     * Gets or creates the gate for a model whose entry declares {@code max_concurrent}.
     *
     * @param model the model
     * @return the gate for this model
     */
    public ModelGate gate(ModelSpec model) {
        return RateLimiterFactory.getInstance().gate(model);
    }
    /**
     * Updates rate limits based on actual API response headers.
     * This allows dynamic adjustment based on real limits.
     *
     * @param model the model
     * @param requestsPerMinute new RPM limit
     * @param tokensPerMinute new TPM limit
     */
    public void updateLimits(ModelSpec model, int requestsPerMinute, int tokensPerMinute) {
        // Delegate to factory for centralized management
        RateLimiterFactory.getInstance().updateLimits(model, requestsPerMinute, tokensPerMinute);
    }

    /**
     * Gets the current status of a model's rate limiter.
     */
    public TokenBucketStatus getStatus(ModelSpec model) {
        TokenBucketRateLimiter limiter = RateLimiterFactory.getInstance().getRateLimiter(model);
        return limiter.getStatus();
    }

    /**
     * Clears all rate limiters (useful for testing).
     */
    public void clear() {
        RateLimiterFactory.getInstance().clear();
    }
}