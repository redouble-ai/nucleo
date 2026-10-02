/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.http.*;
import ai.redouble.nucleo.http.*;
import org.apache.hc.client5.http.classic.methods.*;
import org.apache.hc.core5.http.*;

/**
 * An endpoint that speaks the Chat Completions dialect, over the framework's own HTTP
 * transport: a bearer key on every request, JSON in and out, and every failure typed by the
 * transport - an {@link HttpErrorResponse} for any status the endpoint answered, an
 * {@link ExternalServiceException} without a status when it was not reached - so the clients
 * classify by type and nothing they sent rides in a failure. A 429 keeps the endpoint's
 * {@code retry-after}, the one header a refusal carries that the retry needs.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-15)
 */
final class OpenAIDialectEndpoint extends AbstractApiClient {
    private final String serviceName;
    private final String apiRoot;
    private final String apiKey;

    /**
     * The root the paths are appended to (for example {@code https://api.openai.com/v1}), and
     * the bearer key. What people paste is normalized where the spelling is unambiguous
     * (surrounding whitespace, trailing slashes) and refused where it is not: a root without
     * a scheme is never guessed at, because https would silently break a local http server
     * and http would silently downgrade a remote one. The path stays exactly as provided -
     * whether an endpoint serves under {@code /v1} is the endpoint's business, not a pattern
     * to assume. No root at all is refused the same way, naming where it comes from, since
     * the credential's host part is optional for OpenAI itself and a person can leave it out.
     */
    OpenAIDialectEndpoint(String serviceName, String apiRoot, String apiKey) {
        this.serviceName = serviceName;
        if (apiRoot == null || apiRoot.isBlank()) {
            throw new UncorrectableRuntimeLLMException(serviceName + ": no API root was provided; it is the credential's"
                    + " host part, the root the paths are appended to, e.g. https://router.huggingface.co/v1 or"
                    + " http://localhost:11434/v1");
        }
        String root = apiRoot.strip().replaceAll("/+$", "");
        if (!root.contains("://")) {
            throw new UncorrectableRuntimeLLMException(serviceName + ": the API root '" + root
                    + "' carries no scheme; provide it as the endpoint spells it, e.g."
                    + " https://router.huggingface.co/v1 or http://localhost:11434/v1");
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
        request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
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

    /** {@inheritDoc} Exposed to this package: the dialect's clients post the JSON they built and read the reply's headers. */
    @Override
    protected HttpReply postForReply(String endpoint, String jsonBody) throws LLMReadableCheckedException {
        return super.postForReply(endpoint, jsonBody);
    }

    /** A GET answered as text, for the listing. */
    String getJsonText(String path) throws LLMReadableCheckedException {
        return getText(path, "application/json");
    }
}
