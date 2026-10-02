/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors.http;

import ai.redouble.nucleo.harness.errors.*;

/**
 * HTTP 413 Payload Too Large from an upstream API.
 *
 * <p>The request body exceeded the server's size limit. Specialization of
 * {@link InvalidInputException} - the LLM may retry with smaller content
 * (chunking, truncation, or a different file).
 *
 * <p>The server's own response is carried in {@link HttpErrorDetail} and read
 * through {@link HttpErrorResponse}, so callers can act on the real upstream
 * error, not a hardcoded message.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-14)
 */
public class Http413Exception extends InvalidInputException implements HttpErrorResponse {
    private final HttpErrorDetail http;

    public Http413Exception(String service, String endpoint, String responseBody) {
        this(service, endpoint, responseBody, null);
    }

    public Http413Exception(String service, String endpoint, String responseBody, Throwable cause) {
        this(new HttpErrorDetail(413, service, endpoint, responseBody), cause);
    }

    private Http413Exception(HttpErrorDetail http, Throwable cause) {
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
