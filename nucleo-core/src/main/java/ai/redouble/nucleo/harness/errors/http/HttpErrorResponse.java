/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors.http;

import ai.redouble.nucleo.harness.errors.*;

/**
 * Implemented by every exception that was raised from an HTTP status code, so
 * the server's own response can be read without knowing which of the eleven
 * carriers (the ten {@code HttpNNNException} classes and
 * {@link HttpUnmappedStatusException}) was thrown or which semantic parent it
 * sits under.
 *
 * <p>Catch by the semantic parent when you care about what to DO
 * ({@link InvalidInputException} means the LLM may retry with fixed input);
 * test for this interface when you care about what the server SAID.
 *
 * <p>Implementors supply {@link #http()} and inherit the accessors. Note that a
 * class-declared method beats an interface default, so no implementor may declare
 * these accessors itself or on any superclass - that would silently shadow the
 * carrier.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-16)
 */
public interface HttpErrorResponse {

    /**
     * The server's response: status, service, endpoint, raw body. The one record the carrier
     * built at construction, returned identically on every call.
     */
    HttpErrorDetail http();

    default int getStatusCode() {
        return http().statusCode();
    }

    default String getService() {
        return http().service();
    }

    default String getEndpoint() {
        return http().endpoint();
    }

    default String getResponseBody() {
        return http().responseBody();
    }
}
