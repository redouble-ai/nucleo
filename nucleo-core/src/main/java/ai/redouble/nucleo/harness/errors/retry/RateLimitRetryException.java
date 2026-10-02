/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors.retry;

import ai.redouble.nucleo.harness.admission.*;

import java.time.*;

/**
 * The provider rejected this attempt as over the caller's rate budget (429).
 * Transparent retry with standard pacing and one throttle increment - the caller
 * nudged past its own budget, so a small fleet-wide correction converges. Carries the
 * {@link RateLimitInfo} the 429 response headers supplied; the other retry signals
 * have no headers, which is why the info lives here and not on the parent.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-10-23)
 */
public final class RateLimitRetryException extends UpstreamRetryException {
    private final RateLimitInfo rateLimitInfo;
    private final int attemptNumber;
    private final String providerMessage;
    private final Duration suggestedDelay;

    public RateLimitRetryException(String message, RateLimitInfo rateLimitInfo, int attemptNumber, String providerMessage, Duration suggestedDelay, Throwable cause) {
        super(message, cause);
        this.rateLimitInfo = rateLimitInfo;
        this.attemptNumber = attemptNumber;
        this.providerMessage = providerMessage;
        this.suggestedDelay = suggestedDelay;
    }

    public RateLimitInfo getRateLimitInfo() {
        return rateLimitInfo;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public String getProviderMessage() {
        return providerMessage;
    }

    public Duration getSuggestedDelay() {
        return suggestedDelay;
    }

    @Override
    public long baseMinJitterMs() {
        return 5000;
    }

    @Override
    public long baseMaxJitterMs() {
        return 30000;
    }

    @Override
    public int backpressureIncrements() {
        return 1;
    }

    /**
     * Creates new exception with incremented attempt counter.
     */
    public RateLimitRetryException withIncrementedAttempt() {
        return new RateLimitRetryException(
            getMessage(),
            rateLimitInfo,
            attemptNumber + 1,
            providerMessage,
            suggestedDelay,
            getCause()
        );
    }
}
