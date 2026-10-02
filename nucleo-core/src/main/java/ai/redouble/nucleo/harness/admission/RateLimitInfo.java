/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;

import java.io.*;
import java.time.*;

/**
 * Captures rate limit information from API responses.
 * Immutable data class holding rate limit headers and status.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-08-11)
 */
public class RateLimitInfo implements Serializable {
    private final String model;
    private final Integer tokensLimit;       // From x-ratelimit-limit-tokens
    private final Integer tokensRemaining;   // From x-ratelimit-remaining-tokens
    private final Instant tokensReset;       // From x-ratelimit-reset-tokens
    private final Integer requestsLimit;     // From x-ratelimit-limit-requests
    private final Integer requestsRemaining; // From x-ratelimit-remaining-requests
    private final Duration retryAfter;       // From retry-after header (for 429s)
    private final boolean wasRateLimited;    // True if this came from a 429 response
    private final Instant capturedAt;        // When this info was captured
    private final RateLimitType type;        // Type of rate limit

    // Derived fields for easier access
    public final int requestsPerMinute;      // Requests per minute limit
    public final int tokensPerMinute;        // Tokens per minute limit

    public RateLimitInfo(String model, Integer tokensLimit, Integer tokensRemaining, Instant tokensReset, Integer requestsLimit, Integer requestsRemaining, Duration retryAfter, boolean wasRateLimited) {
        this(model, tokensLimit, tokensRemaining, tokensReset, requestsLimit, requestsRemaining, retryAfter, wasRateLimited, RateLimitType.UNKNOWN);
    }

    /**
     * The full record. A null kind reads as {@link RateLimitType#UNKNOWN}; the per-minute fields
     * mirror the limits and read zero when a limit is absent; the capture instant is now.
     */
    public RateLimitInfo(String model, Integer tokensLimit, Integer tokensRemaining, Instant tokensReset, Integer requestsLimit, Integer requestsRemaining, Duration retryAfter, boolean wasRateLimited, RateLimitType type) {
        this.model = model;
        this.tokensLimit = tokensLimit;
        this.tokensRemaining = tokensRemaining;
        this.tokensReset = tokensReset;
        this.requestsLimit = requestsLimit;
        this.requestsRemaining = requestsRemaining;
        this.retryAfter = retryAfter;
        this.wasRateLimited = wasRateLimited;
        this.type = type != null ? type : RateLimitType.UNKNOWN;
        this.capturedAt = Instant.now();

        // Calculate per-minute rates (assuming 1-minute window)
        this.requestsPerMinute = requestsLimit != null ? requestsLimit : 0;
        this.tokensPerMinute = tokensLimit != null ? tokensLimit : 0;
    }

    /**
     * The minimal record for a limit that was hit and nothing more: no limits known, nothing
     * remaining, a sixty-second retry-after, kind unknown.
     */
    public static RateLimitInfo rateLimited(String model) {
        return new RateLimitInfo(model, null, 0, null, null, 0, Duration.ofSeconds(60), true, RateLimitType.UNKNOWN);
    }

    /**
     * The record a successful response's token headers give: a capacity reading that was not
     * rate limited, with no retry-after and no request limits.
     */
    public static RateLimitInfo fromHeaders(String model, Integer tokensLimit, Integer tokensRemaining, Instant tokensReset) {
        return new RateLimitInfo(model, tokensLimit, tokensRemaining, tokensReset, null, null, null, false, RateLimitType.CAPACITY);
    }

    /** Header-value parsing shared by the clients: the value is trimmed, and a missing or non-numeric header is a valid absent value, null. */
    public static Integer parseIntOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        }
        catch (NumberFormatException nfe) {
            return null;
        }
    }

    // Getters
    public String getModel() {return model;}

    public Integer getTokensLimit() {return tokensLimit;}

    public Integer getTokensRemaining() {return tokensRemaining;}

    public Instant getTokensReset() {return tokensReset;}

    public Integer getRequestsLimit() {return requestsLimit;}

    public Integer getRequestsRemaining() {return requestsRemaining;}

    public Duration getRetryAfter() {return retryAfter;}

    public boolean wasRateLimited() {return wasRateLimited;}

    public Instant getCapturedAt() {return capturedAt;}

    public RateLimitType getType() {return type;}
}