/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors.http;

import ai.redouble.nucleo.harness.errors.*;

/**
 * HTTP 422 Unprocessable Entity from an upstream API.
 *
 * <p>The request was syntactically valid but semantically rejected. Specialization
 * of {@link InvalidInputException} - the LLM may fix the meaning of its input and retry.
 *
 * <p>The server's own response is carried in {@link HttpErrorDetail} and read
 * through {@link HttpErrorResponse}, so callers can act on the real upstream
 * error, not a hardcoded message.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-14)
 */
public class Http422Exception extends InvalidInputException implements HttpErrorResponse {
    private final HttpErrorDetail http;

    public Http422Exception(String service, String endpoint, String responseBody) {
        this(service, endpoint, responseBody, null);
    }

    public Http422Exception(String service, String endpoint, String responseBody, Throwable cause) {
        this(new HttpErrorDetail(422, service, endpoint, responseBody), cause);
    }

    private Http422Exception(HttpErrorDetail http, Throwable cause) {
        super(http.endpoint(), null, http.fromService(), cause);
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
