/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;

import java.time.*;

/**
 * Status snapshot for a token-bucket rate limiter with dual request/token buckets.
 *
 * <p>Used by token-bucket LLM rate limiters (requests per minute + tokens per minute).
 * Exposes the available capacity in both buckets, computed from the clock at the instant
 * the snapshot was taken.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-13)
 */
public record TokenBucketStatus(
        int maxRequests,
        long availableRequests,
        int maxTokens,
        long availableTokens,
        Instant lastRefill
) implements RateLimiterStatus {
    /**
     * True if there is room for a request consuming the given token estimate.
     */
    public boolean hasCapacity(int estimatedTokens) {
        return availableRequests > 0 && availableTokens >= estimatedTokens;
    }

    @Override
    public String summary() {
        return String.format("requests %d/%d, tokens %d/%d",
                availableRequests, maxRequests, availableTokens, maxTokens);
    }
}
