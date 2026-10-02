/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors.http;

import ai.redouble.nucleo.harness.errors.*;

/**
 * HTTP 503 Service Unavailable from an upstream API.
 *
 * <p>The origin server is overloaded or down for maintenance. Specialization
 * of {@link ExternalServiceException}. Often transient and worth retrying
 * after a delay; some servers include a {@code Retry-After} header.
 *
 * <p>The server's own response is carried in {@link HttpErrorDetail} and read
 * through {@link HttpErrorResponse}, so callers can act on the real upstream
 * error, not a hardcoded message.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-14)
 */
public class Http503Exception extends ExternalServiceException implements HttpErrorResponse {
    private final HttpErrorDetail http;

    public Http503Exception(String service, String endpoint, String responseBody) {
        this(service, endpoint, responseBody, null);
    }

    public Http503Exception(String service, String endpoint, String responseBody, Throwable cause) {
        this(new HttpErrorDetail(503, service, endpoint, responseBody), cause);
    }

    private Http503Exception(HttpErrorDetail http, Throwable cause) {
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
