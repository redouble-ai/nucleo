/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors.http;

import ai.redouble.nucleo.harness.errors.*;

/**
 * HTTP 404 Not Found from an upstream API.
 *
 * <p>A well-formed identifier did not resolve to a resource. Specialization
 * of {@link ResourceNotFoundException} - correctable by the LLM, which may
 * try a different identifier.
 *
 * <p>Note: 404 on a SEARCH operation is usually a valid empty result, not
 * an error. Only fetch-by-ID 404s should throw this exception.
 *
 * <p>The server's own response is carried in {@link HttpErrorDetail} and read
 * through {@link HttpErrorResponse}, so callers can act on the real upstream
 * error, not a hardcoded message.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-14)
 */
public class Http404Exception extends ResourceNotFoundException implements HttpErrorResponse {
    private final HttpErrorDetail http;

    public Http404Exception(String service, String endpoint, String responseBody) {
        this(service, endpoint, responseBody, null);
    }

    public Http404Exception(String service, String endpoint, String responseBody, Throwable cause) {
        this(new HttpErrorDetail(404, service, endpoint, responseBody), cause);
    }

    private Http404Exception(HttpErrorDetail http, Throwable cause) {
        super(http.service(), http.endpoint(), cause);
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
