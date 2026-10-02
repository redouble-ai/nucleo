/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors.http;

/**
 * The upstream server's actual response to a failed HTTP call: status code,
 * which service answered, which endpoint was hit, and the raw body it returned.
 *
 * <p>Every {@code HttpNNNException} carries one of these, and so does
 * {@link HttpUnmappedStatusException}. The ten status-specific
 * exception classes exist to slot HTTP semantics into the LLM-readable hierarchy
 * (400 is correctable, 401 is not, 429 feeds the rate limiter), and Java's single
 * inheritance forces them under five different semantic parents. That rules out a
 * shared base class, so the payload they all carry lives here instead and is
 * reached through {@link HttpErrorResponse}.
 *
 * <p>The three formatters differ in what the surrounding context already states.
 * A parent that stores the endpoint separately does not want it repeated in the
 * detail line, and vice versa.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-16)
 */
public record HttpErrorDetail(int statusCode, String service, String endpoint, String responseBody) {

    /**
     * The endpoint is the path that was hit, never the request target. A client builds its
     * target by appending a query string, and that query carries the arguments a caller
     * supplied: a search term, an identifier, whatever was typed into the field. Those are
     * the caller's own text, and an error naming them would hand it back, so the query is
     * cut here, at the one place every one of these is built. What is left is what this
     * record's readers say it is - which endpoint answered, with what status, and what it
     * said.
     */
    public HttpErrorDetail {
        int query = endpoint == null ? -1 : endpoint.indexOf('?');
        if (query >= 0) {
            endpoint = endpoint.substring(0, query);
        }
    }

    /** {@code "HTTP 502 at /v1/fetch: <body>"} - for parents that already store the service name. */
    public String atEndpoint() {
        return "HTTP " + statusCode + " at " + endpoint + ": " + responseBody;
    }

    /** {@code "HTTP 400 from PubMed: <body>"} - for parents that already store the endpoint. */
    public String fromService() {
        return "HTTP " + statusCode + " from " + service + ": " + responseBody;
    }

    /** {@code "HTTP 502 from PubMed at /v1/fetch: <body>"} - the LLM-facing form, naming both. */
    public String llmMessage() {
        return "HTTP " + statusCode + " from " + service + " at " + endpoint + ": " + responseBody;
    }
}
