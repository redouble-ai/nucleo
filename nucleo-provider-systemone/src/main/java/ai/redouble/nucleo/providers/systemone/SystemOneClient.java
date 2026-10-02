/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.systemone;

import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.decision.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.http.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.http.*;
import ai.redouble.nucleo.secrets.*;
import org.apache.hc.core5.http.*;

import java.io.*;
import java.time.*;

/**
 * The decision client over a System One endpoint. The endpoint is the {@code host} part of
 * the credential its provider owns, the API root: {@link #SECRET_ID} for a TypeSafe-compatible
 * endpoint, which also carries the bearer key, or {@link #LOCAL_ID} for a server on this
 * machine, which carries only the address and is called with no key. The client posts the
 * wire body the runtime encodes to {@code /v1/systemone} under it and reads the answers back
 * with the runtime's codec, so this class carries only the transport: the bearer key when
 * there is one, the request id header an endpoint may answer with, and the reading of a
 * failure by the status it answered.
 *
 * <p>Failures are read off the types in the cause chain, never off the words of the request:
 * a 429 is a rate limit with whatever {@code retry-after} it carried, a 5xx or no answer at
 * all is a server error the dispatcher paces, and a 2xx whose body is not an answer is a
 * service failure naming what was missing.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public class SystemOneClient extends AbstractDecisionClient {
    /** The TypeSafe-compatible credential: the bearer key the endpoint checks and, as its host, the API root. */
    public static final String SECRET_ID = "systemone-api-key";
    public static final CredentialShape SHAPE = CredentialShape.secretAndHost(SECRET_ID,
            "SYSTEMONE_API_KEY", "the bearer token the endpoint checks",
            "SYSTEMONE_API_KEY_HOST", "the API root the path is appended to, e.g. https://api.typesafe.ai");
    /** The local credential: only the address of a server on this machine, which checks no key. */
    public static final String LOCAL_ID = "systemone-local";
    public static final CredentialShape LOCAL_SHAPE = CredentialShape.host(LOCAL_ID,
            "SYSTEMONE_LOCAL_HOST", "the address of the server on this machine, e.g. http://127.0.0.1:8009");
    static final String SERVICE_NAME = "System One endpoint";
    /** The request id header the endpoints of this class answer with, when they do. */
    static final String REQUEST_ID_HEADER = "x-typesafe-request-id";
    private final String credentialId;
    private SystemOneEndpoint endpoint;
    private String apiKey;
    private String apiRoot;

    /** A client reading its endpoint from the credential its provider owns. */
    public SystemOneClient(String credentialId) {
        this.credentialId = credentialId;
    }

    /** An explicit endpoint and key, for a harness that has them in hand; a null key sends none. */
    public SystemOneClient(String apiRoot, String apiKey) {
        this.credentialId = null;
        this.apiRoot = apiRoot;
        this.apiKey = apiKey;
    }

    private SystemOneEndpoint endpoint() {
        if (endpoint == null) {
            if (apiRoot == null) {
                Credential credential = Secrets.configured().require(credentialId);
                if (credential.host() == null || credential.host().isBlank()) {
                    throw new UncorrectableRuntimeLLMException("The credential '" + credentialId
                            + "' carries no host; its host part is the API root this client posts to (e.g. http://127.0.0.1:8009)");
                }
                apiKey = credential.secret();
                apiRoot = credential.host();
            }
            endpoint = new SystemOneEndpoint(SERVICE_NAME, apiRoot, apiKey);
        }
        return endpoint;
    }

    @Override
    protected RawDecision doDecide(DecisionRequest request) throws IOException {
        String body = SystemOneWire.encode(model.getWireModelId(), request);
        HttpReply reply;
        try {
            reply = endpoint().postForReply(SystemOneWire.PATH, body);
        }
        catch (LLMReadableCheckedException e) {
            // The template classifies an IOException by walking its causes; the typed failure is the cause
            throw new IOException(describe(e), e);
        }
        SystemOneWire.Decoded decoded;
        try {
            decoded = SystemOneWire.decode(reply.body(), request);
        }
        catch (IOException e) {
            // A 2xx that is not an answer: a service failure with no status, named by what was missing
            throw new IOException(SERVICE_NAME + " answered for " + model.getId() + " with something that is not an answer: " + e.getMessage(),
                    new ExternalServiceException(SERVICE_NAME, SystemOneWire.PATH + " answered with something that is not an answer: " + e.getMessage(), e));
        }
        return new RawDecision(decoded, requestId(reply.headers()));
    }

    private static String requestId(Header[] headers) {
        if (headers == null) {
            return null;
        }
        for (Header header : headers) {
            if (REQUEST_ID_HEADER.equalsIgnoreCase(header.getName())) {
                return header.getValue();
            }
        }
        return null;
    }

    /** What to tell a thinker: the status and the model, never the request. */
    private String describe(Throwable e) {
        HttpErrorResponse http = answered(e);
        return http != null
                ? SERVICE_NAME + " answered HTTP " + http.getStatusCode() + " for " + model.getId()
                : SERVICE_NAME + " could not be reached for " + model.getId();
    }

    /** The status the endpoint answered, or null when the failure carries none. */
    private static HttpErrorResponse answered(Throwable e) {
        for (Throwable current = e; current != null; current = current.getCause()) {
            if (current instanceof HttpErrorResponse http) {
                return http;
            }
        }
        return null;
    }

    @Override
    protected boolean is429Error(Exception e) {
        HttpErrorResponse http = answered(e);
        return http != null && http.getStatusCode() == 429;
    }

    @Override
    protected String accountIdentifier() {
        return credentialId != null ? "SYSTEMONE / credential " + credentialId : "SYSTEMONE / " + apiRoot;
    }

    @Override
    protected RateLimitInfo extractRateLimitInfo(Exception e) {
        if (!is429Error(e)) {
            return null;
        }
        Duration retryAfter = null;
        for (Throwable current = e; current != null; current = current.getCause()) {
            if (current instanceof Http429Exception throttled && throttled.getRetryAfterSeconds() > 0) {
                retryAfter = Duration.ofSeconds(throttled.getRetryAfterSeconds());
            }
        }
        return new RateLimitInfo(model != null ? model.getId() : "unknown", null, null, null, null, null, retryAfter, true, RateLimitType.CAPACITY);
    }
}
