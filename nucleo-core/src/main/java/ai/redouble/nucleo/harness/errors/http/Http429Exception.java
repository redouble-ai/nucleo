/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors.http;

import ai.redouble.nucleo.harness.admission.*;

/**
 * HTTP 429 Too Many Requests from an upstream API.
 *
 * <p>Specialization of {@link UpstreamThrottleException}. Caught by the
 * dispatcher's signaling path which calls
 * {@link RateLimiter#onRateLimitError} on every custom rate limiter
 * attached to the failing job.
 *
 * <p>The server's own response is carried in {@link HttpErrorDetail} and read
 * through {@link HttpErrorResponse}, so callers can act on the real upstream
 * error, not a hardcoded message. The {@code Retry-After} the server asked for
 * stays on the parent as {@link #getRetryAfterSeconds()}, since it is an
 * instruction rather than part of the response payload.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-13)
 */
public class Http429Exception extends UpstreamThrottleException implements HttpErrorResponse {
    private final HttpErrorDetail http;

    public Http429Exception(String service, String endpoint, String responseBody) {
        this(service, endpoint, responseBody, 0, null);
    }

    public Http429Exception(String service, String endpoint, String responseBody, int retryAfterSeconds) {
        this(service, endpoint, responseBody, retryAfterSeconds, null);
    }

    public Http429Exception(String service, String endpoint, String responseBody, int retryAfterSeconds, Throwable cause) {
        this(new HttpErrorDetail(429, service, endpoint, responseBody), retryAfterSeconds, cause);
    }

    private Http429Exception(HttpErrorDetail http, int retryAfterSeconds, Throwable cause) {
        super(http.service(), http.atEndpoint(), retryAfterSeconds, cause);
        this.http = http;
    }

    @Override
    public HttpErrorDetail http() {
        return http;
    }

    @Override
    public String getLLMMessage() {
        return http.llmMessage();
    }
}
