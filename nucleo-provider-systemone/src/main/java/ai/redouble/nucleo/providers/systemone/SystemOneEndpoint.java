/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.systemone;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.http.*;
import ai.redouble.nucleo.http.*;
import org.apache.hc.client5.http.classic.methods.*;
import org.apache.hc.core5.http.*;
import com.fasterxml.jackson.databind.*;

/**
 * An endpoint that speaks the System One wire, over the framework's own HTTP transport: a
 * bearer key on every request when the credential carries one, JSON in and out, and every failure typed by the transport -
 * an {@link HttpErrorResponse} for any status the endpoint answered, an
 * {@link ExternalServiceException} without a status when it was not reached - so the client
 * classifies by type and nothing it sent rides in a failure. A 429 keeps the endpoint's
 * {@code retry-after}, the one header a refusal carries that the retry needs.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
final class SystemOneEndpoint extends AbstractApiClient {
    private final String serviceName;
    private final String apiRoot;
    private final String apiKey;

    /**
     * The root the path is appended to (for example {@code https://api.typesafe.ai} or
     * {@code http://127.0.0.1:8009}) and the bearer key, null for a server that checks none. Surrounding whitespace and trailing
     * slashes are dropped; a root without a scheme is refused rather than guessed at, because
     * https would silently break a local http server and http would silently downgrade a
     * remote one.
     */
    SystemOneEndpoint(String serviceName, String apiRoot, String apiKey) {
        this.serviceName = serviceName;
        String root = apiRoot.strip().replaceAll("/+$", "");
        if (!root.contains("://")) {
            throw new UncorrectableRuntimeLLMException(serviceName + ": the API root '" + root
                    + "' carries no scheme; provide it as the endpoint spells it, e.g."
                    + " https://api.typesafe.ai or http://127.0.0.1:8009");
        }
        this.apiRoot = root;
        this.apiKey = apiKey;
    }

    @Override
    protected String getServiceName() {
        return serviceName;
    }

    @Override
    protected String getBaseUrl() {
        return apiRoot;
    }

    @Override
    protected void decorateRequest(HttpUriRequestBase request) {
        // a local server checks no key, and its credential carries none to send
        if (apiKey != null) {
            request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
    }

    @Override
    protected LLMReadableCheckedException classify(String endpoint, int statusCode, Header[] headers, String responseBody) {
        if (statusCode != 429) {
            return null;
        }
        int retryAfterSeconds = 0;
        for (Header header : headers) {
            if ("retry-after".equalsIgnoreCase(header.getName())) {
                try {
                    retryAfterSeconds = Integer.parseInt(header.getValue().trim());
                }
                catch (NumberFormatException e) {
                    // an HTTP-date form of retry-after carries no seconds this reads
                }
            }
        }
        return new Http429Exception(serviceName, endpoint, responseBody, retryAfterSeconds);
    }

    /** {@inheritDoc} Exposed to this package: the client posts the JSON it built and reads the reply's headers. */
    @Override
    protected HttpReply postForReply(String endpoint, String jsonBody) throws LLMReadableCheckedException {
        return super.postForReply(endpoint, jsonBody);
    }

    /** {@inheritDoc} Exposed to this package: the listing reads the endpoint's models. */
    @Override
    protected JsonNode get(String path) throws LLMReadableCheckedException {
        return super.get(path);
    }
}
