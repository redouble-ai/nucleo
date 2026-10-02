/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors.http;

import ai.redouble.nucleo.harness.errors.*;

/**
 * An error status with no class of its own - a 402, a 408, a 520 from a proxy - carried with
 * its status, service, endpoint and body like the mapped ones, so a caller judging by
 * {@link HttpErrorResponse} sees every status the service answered and never mistakes an
 * unmapped status for a failure to reach the service, which has no status at all.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-15)
 */
public class HttpUnmappedStatusException extends ExternalServiceException implements HttpErrorResponse {
    private final HttpErrorDetail http;

    public HttpUnmappedStatusException(int statusCode, String service, String endpoint, String responseBody) {
        this(new HttpErrorDetail(statusCode, service, endpoint, responseBody));
    }

    private HttpUnmappedStatusException(HttpErrorDetail http) {
        super(http.service(), http.atEndpoint());
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
