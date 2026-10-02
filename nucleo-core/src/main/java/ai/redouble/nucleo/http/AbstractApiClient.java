/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.http;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.http.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.*;
import org.apache.hc.client5.http.classic.methods.*;
import org.apache.hc.client5.http.impl.classic.*;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.io.entity.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;

/**
 * Shared base for external-service API clients.
 *
 * <p>Handles the HTTP transport plumbing common to every integration: request
 * execution, body reading (a status without an entity reads as an empty body),
 * status-code-to-exception mapping via {@link HttpExceptions} for every status
 * from 300 up (a 3xx arrives only when the request disabled redirects, and is
 * then an answer the client must not act on), with the upstream body quoted
 * whole, and a small set of override points for per-service customization.
 * A service that could not be reached, and a 2xx body the parser refuses, are
 * each an {@link ExternalServiceException} with no status. Every failure names
 * the endpoint as its path alone, the query cut, and carries nothing else of the
 * request: no header, no body.
 *
 * <p>Subclasses provide:
 * <ul>
 *   <li>{@link #getServiceName()} - name reported in exceptions and logs.
 *   <li>{@link #getBaseUrl()} - URL prefix prepended to every path.
 *   <li>Typed API methods (e.g. {@code getMolecule(id)}, {@code search(query)})
 *       that internally call {@link #get} / {@link #post}.
 * </ul>
 *
 * <p>Hooks for service-specific behavior (all optional):
 * <ul>
 *   <li>{@link #buildUrl(String)} - intercept URL construction, e.g. to append
 *       a mandatory {@code api_key} query parameter.
 *   <li>{@link #decorateRequest(HttpUriRequestBase)} - add auth headers or
 *       custom metadata to every request.
 *   <li>{@link #classify(int, Header[], String)} - map service-specific
 *       response signals to typed exceptions when the HTTP status code alone
 *       is not enough (e.g. EPO's {@code CLIENT.RobotDetected} body on a 403
 *       mapped to {@link Http429Exception} so the dispatcher signals the rate
 *       limiter); {@link #classify(String, int, Header[], String)} is the same
 *       hook with the endpoint, for a message that must name where it failed.
 *   <li>{@link #postProcessResponse(int, Header[], String)} - inspect every
 *       response for side-channel pressure signals (e.g. EPO's
 *       {@code X-Throttling-Control} header).
 * </ul>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-14)
 */
public abstract class AbstractApiClient {
    protected CloseableHttpClient httpClient;

    public void setHttpClient(CloseableHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /** Service name reported in exceptions and logs. */
    protected abstract String getServiceName();

    /** URL prefix prepended to every request path. */
    protected abstract String getBaseUrl();

    /**
     * Constructs a full URL from a path. Default: {@code getBaseUrl() + path}.
     * Override when a service requires a query parameter on every request
     * (e.g. NCBI's {@code api_key}).
     */
    protected String buildUrl(String path) {
        return getBaseUrl() + path;
    }

    /**
     * Adds per-request headers or metadata. Default: no-op. Override to attach
     * {@code Authorization} headers, custom {@code User-Agent}, etc.
     */
    protected void decorateRequest(HttpUriRequestBase request) {
    }

    /**
     * Inspects the raw response and optionally returns a typed exception that
     * overrides the standard status-code mapping. Return {@code null} to fall
     * through to {@link HttpExceptions#fromStatus}.
     *
     * <p>Use for service-specific error patterns that do not map cleanly to
     * HTTP status codes.
     */
    protected LLMReadableCheckedException classify(int statusCode, Header[] headers, String responseBody) {
        return null;
    }

    /**
     * The same hook with the endpoint the response came from, for a classification whose
     * message must name where it failed. Default: the endpoint-less form.
     */
    protected LLMReadableCheckedException classify(String endpoint, int statusCode, Header[] headers, String responseBody) {
        return classify(statusCode, headers, responseBody);
    }

    /**
     * Called on every response regardless of status. Use for side-channel
     * pressure signals that are not errors themselves, such as throttling
     * hints in response headers.
     */
    protected void postProcessResponse(int statusCode, Header[] headers, String responseBody) {
    }

    /**
     * Executes a JSON GET against {@code path}, returning the parsed tree.
     * Path is resolved through {@link #buildUrl}, the request is decorated
     * via {@link #decorateRequest}, and any status from 300 up is mapped via
     * {@link HttpExceptions#fromStatus}.
     */
    protected JsonNode get(String path) throws LLMReadableCheckedException {
        HttpGet request = new HttpGet(buildUrl(path));
        request.setHeader("Accept", "application/json");
        return executeRequest(request, path, NucleoJsonSerializer::readTree);
    }

    /**
     * Executes a GET and returns the raw response body as a String. Used
     * when the upstream service returns non-JSON payloads (plain text, XML,
     * CSV). Caller supplies the {@code Accept} header value.
     */
    protected String getText(String path, String acceptType) throws LLMReadableCheckedException {
        HttpGet request = new HttpGet(buildUrl(path));
        request.setHeader("Accept", acceptType);
        return executeRequest(request, path, body -> body);
    }

    /**
     * Executes a JSON POST against {@code endpoint} with {@code body} serialized
     * to JSON and parsed back to {@code responseType}.
     */
    protected <T> T post(String endpoint, Object body, Class<T> responseType) throws LLMReadableCheckedException {
        HttpPost request = new HttpPost(buildUrl(endpoint));
        request.setEntity(new StringEntity(NucleoJsonSerializer.write(body), ContentType.APPLICATION_JSON));
        return executeRequest(request, endpoint, b -> NucleoJsonSerializer.parse(b, responseType));
    }

    /**
     * Executes a JSON POST and returns the parsed JSON tree. Convenience for
     * services where responses are inspected via {@link JsonNode} rather than
     * mapped to a POJO.
     */
    protected JsonNode postForJson(String endpoint, Object body) throws LLMReadableCheckedException {
        HttpPost request = new HttpPost(buildUrl(endpoint));
        request.setEntity(new StringEntity(NucleoJsonSerializer.write(body), ContentType.APPLICATION_JSON));
        return executeRequest(request, endpoint, NucleoJsonSerializer::readTree);
    }

    /**
     * Executes a POST carrying {@code jsonBody} as written and returns the whole reply -
     * status, headers, body - once the error mapping has let it through. For a client that
     * builds its own JSON and reads account facts off the response headers (a provider's
     * rate-limit family, a request id), which a body-only parser cannot see.
     */
    protected HttpReply postForReply(String endpoint, String jsonBody) throws LLMReadableCheckedException {
        HttpPost request = new HttpPost(buildUrl(endpoint));
        request.setEntity(new StringEntity(jsonBody, ContentType.APPLICATION_JSON));
        request.setHeader("Accept", "application/json");
        return executeForReply(request, endpoint);
    }

    /**
     * Core HTTP execution path. Applies {@link #decorateRequest}, runs the
     * request, reads the body, invokes {@link #postProcessResponse} and
     * {@link #classify}, then maps any error status via {@link HttpExceptions}.
     * Successful responses are handed to {@code parser}; a body the parser
     * refuses is an {@link ExternalServiceException} with no status, naming the
     * endpoint path and the parser's complaint.
     */
    protected <T> T executeRequest(HttpUriRequestBase request, String endpoint, ResponseParser<T> parser) throws LLMReadableCheckedException {
        HttpReply reply = executeForReply(request, endpoint);
        try {
            return parser.parse(reply.body());
        }
        catch (IOException e) {
            throw new ExternalServiceException(getServiceName(), pathOf(endpoint) + " failed: " + e.getMessage(), e);
        }
    }

    /**
     * The transport step of {@link #executeRequest}: the reply that cleared the error mapping.
     * The transport is the injected client, else the shared pool. A failure to reach the
     * service at all - refused, reset, closed, timed out - is an {@link ExternalServiceException}
     * that is no {@link ai.redouble.nucleo.harness.errors.http.HttpErrorResponse}, since there
     * is no status; it names the endpoint path and the transport's complaint, and nothing of
     * the request rides in it.
     */
    private HttpReply executeForReply(HttpUriRequestBase request, String endpoint) throws LLMReadableCheckedException {
        decorateRequest(request);
        CloseableHttpClient transport = httpClient != null ? httpClient : HttpConnectionPools.getInstance().getClient();
        HttpReply reply;
        try {
            // Read whole, then judge: a status that carries no body (204, 304) reads as an
            // empty body, and the status mapping and the parser judge that like any other
            // empty answer.
            reply = transport.execute(request, HttpReply.reader());
        }
        catch (IOException e) {
            throw new ExternalServiceException(getServiceName(), pathOf(endpoint) + " failed: " + e.getMessage(), e);
        }
        int statusCode = reply.status();
        Header[] headers = reply.headers();
        String body = reply.body();
        postProcessResponse(statusCode, headers, body);
        LLMReadableCheckedException classified = classify(endpoint, statusCode, headers, body);
        if (classified != null) {
            throw classified;
        }
        // A 3xx reaches here only when the request disabled redirects; it is then an answer
        // this client must not act on, named with its endpoint like any other error.
        if (statusCode >= 300) {
            throw HttpExceptions.fromStatus(getServiceName(), endpoint, statusCode, body);
        }
        return reply;
    }

    /**
     * The endpoint as a failure names it: the path alone. A query carries the caller's own
     * text (a search term, an identifier), which no failure hands back; the status carriers
     * cut it in {@link HttpErrorDetail}, and the two status-less failures here cut it the
     * same way.
     */
    private static String pathOf(String endpoint) {
        int query = endpoint == null ? -1 : endpoint.indexOf('?');
        return query >= 0 ? endpoint.substring(0, query) : endpoint;
    }

    /** URL-encodes a query parameter value using UTF-8. Shared helper for every client. */
    public static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
