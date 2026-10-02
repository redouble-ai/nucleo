/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors.http;

import ai.redouble.nucleo.harness.errors.*;

/**
 * HTTP 403 Forbidden from an upstream API.
 *
 * <p>Credentials are valid but the server refuses access. Specialization of
 * {@link UnauthorizedException} - uncorrectable by the LLM. Distinct from
 * {@link Http401Exception} because 403 often signals stronger conditions:
 * permission revoked, IP block, anti-abuse / robot detection, jailbreak
 * trigger, or geo-restriction. Catch this specifically when you need to
 * react to those conditions (e.g. flag suspected jailbreak attempts).
 *
 * <p>The server's own response is carried in {@link HttpErrorDetail} and read
 * through {@link HttpErrorResponse}, so callers can act on the real upstream
 * error, not a hardcoded message.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-14)
 */
public class Http403Exception extends UnauthorizedException implements HttpErrorResponse {
    private final HttpErrorDetail http;

    public Http403Exception(String service, String endpoint, String responseBody) {
        this(service, endpoint, responseBody, null);
    }

    public Http403Exception(String service, String endpoint, String responseBody, Throwable cause) {
        this(new HttpErrorDetail(403, service, endpoint, responseBody), cause);
    }

    private Http403Exception(HttpErrorDetail http, Throwable cause) {
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
