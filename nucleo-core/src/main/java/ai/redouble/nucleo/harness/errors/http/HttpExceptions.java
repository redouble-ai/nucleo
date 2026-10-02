/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors.http;

import ai.redouble.nucleo.harness.errors.*;
/**
 * Factory for typed HTTP exceptions.
 *
 * <p>Use {@link #fromStatus(String, String, int, String)} from any HTTP client
 * to map an upstream status code to the framework's typed exception hierarchy.
 * Centralizes the switch so call sites are one line instead of a per-service
 * copy of the same ten cases. The factory sees the status and the body only: a
 * client that read a {@code Retry-After} header constructs its
 * {@link Http429Exception} itself with the seconds the server asked for.
 *
 * <p>Status codes without a dedicated class (anything outside the supported
 * set) become an {@link HttpUnmappedStatusException}, still an
 * {@link HttpErrorResponse} carrying the service, endpoint, code, and body, so
 * that only a failure to reach the service is ever without a status.
 *
 * <p>Special-case mappings (EPO's 403 with a robot-detection body, which its
 * client raises as an {@link Http429Exception}) must be detected by the caller
 * BEFORE delegating to the factory.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-14)
 */
public final class HttpExceptions {
    private HttpExceptions() {}

    /**
     * Throws the matching typed exception if {@code statusCode} indicates a
     * client or server error (>= 400). Returns silently for any 1xx/2xx/3xx
     * status. Use this as the standard HTTP error gate at the bottom of every
     * API client method:
     * <pre>{@code
     * String body = EntityUtils.toString(response.getEntity());
     * HttpExceptions.throwIfError("Exa", endpoint, response.getCode(), body);
     * return parse(body);
     * }</pre>
     */
    public static void throwIfError(String service, String endpoint, int statusCode, String responseBody) throws LLMReadableCheckedException {
        if (statusCode >= 400) {
            throw fromStatus(service, endpoint, statusCode, responseBody);
        }
    }

    /**
     * Maps an upstream HTTP status code to the matching typed exception. The
     * returned exception carries {@code service}, {@code endpoint}, and
     * {@code responseBody} as structured fields, queryable from any catch site.
     *
     * <p>Most callers should prefer {@link #throwIfError} which combines the
     * status check and the throw. Use {@code fromStatus} directly only when
     * you need the exception object without throwing it.
     */
    public static LLMReadableCheckedException fromStatus(String service, String endpoint, int statusCode, String responseBody) {
        return switch (statusCode) {
            case 400 -> new Http400Exception(service, endpoint, responseBody);
            case 401 -> new Http401Exception(service, endpoint, responseBody);
            case 403 -> new Http403Exception(service, endpoint, responseBody);
            case 404 -> new Http404Exception(service, endpoint, responseBody);
            case 413 -> new Http413Exception(service, endpoint, responseBody);
            case 422 -> new Http422Exception(service, endpoint, responseBody);
            case 429 -> new Http429Exception(service, endpoint, responseBody);
            case 500 -> new Http500Exception(service, endpoint, responseBody);
            case 502 -> new Http502Exception(service, endpoint, responseBody);
            case 503 -> new Http503Exception(service, endpoint, responseBody);
            default -> new HttpUnmappedStatusException(statusCode, service, endpoint, responseBody);
        };
    }
}
