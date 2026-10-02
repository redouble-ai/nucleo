/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors.retry;


/**
 * A plain transient provider failure: 5xx errors, connection failures, unparseable
 * responses. Transparent retry with standard pacing and no throttle feedback - a
 * fault is not a slow-down request, and cutting the fleet's admission rate over a
 * stray 500 would be an overreaction. A 529 is deliberately NOT this: overload is a
 * capacity signal and rides {@link OverloadRetryException}.
 *
 * <p>Semantically separate from {@link RateLimitRetryException} (429) because
 * provider failover triggers on sustained 5xx only, never on rate limits.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-07)
 * @see RateLimitRetryException
 */
public final class TransientErrorRetryException extends UpstreamRetryException {
    private final String provider;
    private final String errorDetails;
    private final int httpStatus;
    private final int attemptNumber;

    public TransientErrorRetryException(String message, String provider, String errorDetails, int httpStatus, int attemptNumber, Throwable cause) {
        super(message, cause);
        this.provider = provider;
        this.errorDetails = errorDetails;
        this.httpStatus = httpStatus;
        this.attemptNumber = attemptNumber;
    }

    public String getProvider() {
        return provider;
    }

    public String getErrorDetails() {
        return errorDetails;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public int getAttemptNumber() {
        return attemptNumber;
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
        return 0;
    }

    /**
     * Creates new exception with incremented attempt counter.
     */
    public TransientErrorRetryException withIncrementedAttempt() {
        return new TransientErrorRetryException(getMessage(), provider, errorDetails, httpStatus, attemptNumber + 1, getCause());
    }
}
