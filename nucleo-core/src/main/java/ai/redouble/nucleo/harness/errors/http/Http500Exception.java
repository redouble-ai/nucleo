/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors.http;

import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.errors.*;

/**
 * HTTP 500 Internal Server Error from an upstream API.
 *
 * <p>Specialization of {@link ExternalServiceException} for an unspecified
 * server-side failure. Caught by the dispatcher's signaling path which calls
 * {@link RateLimiter#onRateLimitError} on every custom rate limiter attached
 * to the failing job.
 *
 * <p>The server's own response is carried in {@link HttpErrorDetail} and read
 * through {@link HttpErrorResponse}, so callers can act on the real upstream
 * error, not a hardcoded message.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-14)
 */
public class Http500Exception extends ExternalServiceException implements HttpErrorResponse {
    private final HttpErrorDetail http;

    public Http500Exception(String service, String endpoint, String responseBody) {
        this(service, endpoint, responseBody, null);
    }

    public Http500Exception(String service, String endpoint, String responseBody, Throwable cause) {
        this(new HttpErrorDetail(500, service, endpoint, responseBody), cause);
    }

    private Http500Exception(HttpErrorDetail http, Throwable cause) {
        super(http.service(), http.atEndpoint(), cause);
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
