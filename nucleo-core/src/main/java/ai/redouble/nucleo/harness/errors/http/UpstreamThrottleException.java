/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors.http;

import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.errors.*;
/**
 * Signals that an upstream API is throttling us.
 *
 * <p>A specialized {@link ExternalServiceException} thrown by tools when they detect
 * a rate-limit or throttle signal from an upstream API: a 429 status (as
 * {@link Http429Exception}), a {@code Retry-After} header, or an API-specific
 * indicator such as EPO OPS' {@code X-Throttling-Control: search=black:0}. EPO's
 * 403 with a {@code CLIENT.RobotDetected} body is raised by its client as an
 * {@link Http429Exception}.
 *
 * <p>Caught strategically by the dispatcher, which then signals
 * {@link RateLimiter#onRateLimitError} on every
 * custom rate limiter attached to the failing job. The exception is rethrown
 * and reaches the LLM as an uncorrectable error so it tries a different approach.
 *
 * <p>The {@code retryAfterSeconds} value is advisory and may be {@code 0} if the
 * upstream did not provide a hint.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-13)
 */
public class UpstreamThrottleException extends ExternalServiceException {
    private final int retryAfterSeconds;
    public UpstreamThrottleException(String serviceName, String errorDetails) {
        super(serviceName, errorDetails);
        this.retryAfterSeconds = 0;
    }
    public UpstreamThrottleException(String serviceName, String errorDetails, int retryAfterSeconds) {
        super(serviceName, errorDetails);
        this.retryAfterSeconds = retryAfterSeconds;
    }
    public UpstreamThrottleException(String serviceName, String errorDetails, int retryAfterSeconds, Throwable cause) {
        super(serviceName, errorDetails, cause);
        this.retryAfterSeconds = retryAfterSeconds;
    }
    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
