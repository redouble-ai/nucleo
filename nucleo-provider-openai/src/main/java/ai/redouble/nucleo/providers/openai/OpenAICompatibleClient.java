/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.http.*;
import ai.redouble.nucleo.secrets.*;
import com.fasterxml.jackson.databind.node.*;
import org.apache.hc.core5.http.*;

import java.util.*;

/**
 * The Chat Completions dialect over the framework's own HTTP transport, for the endpoints that
 * speak it without being OpenAI: a self-hosted server, an aggregator, a vendor with an
 * OpenAI-shaped API. Those diverge from OpenAI in the details an official SDK is strict about
 * (unknown fields, missing usage blocks, header names), so this transport asks for nothing
 * beyond the JSON body and reads what comes back leniently.
 *
 * <p>The endpoint is the {@code host} part of the {@link #SECRET_ID} credential, the API root
 * ({@code https://router.huggingface.co/v1} for Hugging Face Inference Providers,
 * {@code http://localhost:11434/v1} for a local Ollama); this client appends the path of the API its
 * provider serves, {@code /chat/completions} under {@link OpenAICompatibleProvider} or
 * {@code /responses} under {@link OpenAICompatibleResponsesProvider}, since a third-party
 * endpoint decides which it speaks. OpenAI itself is served by {@link OpenAISDKClient}.
 *
 * <p>A failure is judged by what the endpoint answered and nothing else: the transport types
 * every status ({@link OpenAIDialectEndpoint}), the reading is {@link OpenAIDialectFailures},
 * and what reaches a thinker is the framework's uncorrectable failure naming the status and
 * the model, with the typed failure as its cause for the template's 429, quota and 5xx
 * classification. The conversation that was sent never appears in any of it.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
public class OpenAICompatibleClient extends AbstractOpenAIChatClient {
    /** The credential: {@code secret} is the bearer token, {@code host} the API root the paths are appended to. */
    public static final String SECRET_ID = "openai-compatible-api-key";
    /** What the credential is made of, as every provider riding this id declares it. */
    public static final CredentialShape SHAPE = CredentialShape.secretAndHost(SECRET_ID, "OPENAI_COMPATIBLE_API_KEY", "the bearer token",
            "OPENAI_COMPATIBLE_API_KEY_HOST", "the API root the paths are appended to, e.g. https://router.huggingface.co/v1");
    static final String SERVICE_NAME = "OpenAI-compatible endpoint";
    static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    static final String RESPONSES_PATH = "/responses";
    private OpenAIDialectEndpoint endpoint;
    private String apiKey;
    private String apiRoot;

    /** The endpoint and key come from the store on first use, so the request builder is exercisable without either. */
    public OpenAICompatibleClient(WireApi wireApi) {
        super(wireApi);
    }

    /** An explicit endpoint and key, for a harness that has them in hand. */
    public OpenAICompatibleClient(WireApi wireApi, String apiRoot, String apiKey) {
        super(wireApi);
        this.apiRoot = apiRoot;
        this.apiKey = apiKey;
    }

    /**
     * The transport, built on first use rather than at construction. Reading the credential is
     * what a request needs, not what an instance needs, so deferring it lets the request
     * builder be exercised without one.
     */
    private OpenAIDialectEndpoint endpoint() {
        if (endpoint == null) {
            if (apiKey == null || apiRoot == null) {
                Credential credential = Secrets.configured().require(SECRET_ID);
                if (credential.host() == null || credential.host().isBlank()) {
                    throw new UncorrectableRuntimeLLMException("The credential '" + SECRET_ID
                            + "' carries no host; its host part is the API root this client posts to (e.g. https://router.huggingface.co/v1)");
                }
                apiKey = apiKey != null ? apiKey : credential.secret();
                apiRoot = apiRoot != null ? apiRoot : credential.host();
            }
            endpoint = new OpenAIDialectEndpoint(SERVICE_NAME, apiRoot, apiKey);
        }
        return endpoint;
    }

    /** This client's dialect, posted to that API's path under the root. */
    @Override
    protected <T> LLMResponse<T> doSingleResponse(LLMRequest<T> request, PreparedConversation prepared) {
        LLMResponse<T> response = new LLMResponse<T>(request);
        boolean responses = wireApi() == WireApi.RESPONSES;
        ObjectNode requestJson = responses ? buildResponsesJson(request, prepared) : buildRequestJson(request, prepared);
        // Capture the wire payloads onto LLMRequest before/after so the audit trail records
        // exactly what we sent and received
        String requestStr = requestJson.toString();
        request.setInputJson(requestStr);
        HttpReply reply;
        try {
            reply = endpoint().postForReply(responses ? RESPONSES_PATH : CHAT_COMPLETIONS_PATH, requestStr);
        }
        catch (LLMReadableCheckedException e) {
            // The typed failure stays the cause: the template classifies 429, quota and 5xx by
            // walking the chain, and what is left reaches a thinker as this uncorrectable failure
            throw new UncorrectableRuntimeLLMException(OpenAIDialectFailures.describe(e, SERVICE_NAME, getModel().getId()), e);
        }
        captureEnvelope(response, reply);
        return responses ? finishResponses(response, request, reply.body()) : finishResponse(response, request, reply.body());
    }

    /**
     * Records the provider envelope: all response headers verbatim (lowercased names, first
     * value each), the provider's request id, and the account's live per-model rate limits
     * from the x-ratelimit-* family when the endpoint sends them, feeding the pre-emptive
     * limiter updates.
     */
    void captureEnvelope(LLMResponse<?> response, HttpReply reply) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Header header : reply.headers()) {
            headers.putIfAbsent(header.getName().toLowerCase(Locale.ROOT), header.getValue());
        }
        recordEnvelope(response, headers);
    }

    @Override
    protected boolean is429Error(Exception e) {
        return OpenAIDialectFailures.is429(e);
    }

    @Override
    protected boolean isQuotaError(Exception e) {
        return OpenAIDialectFailures.isQuota(e);
    }

    @Override
    protected String accountIdentifier() {
        return "OPENAI-COMPATIBLE / secret " + SECRET_ID;
    }

    @Override
    protected boolean isServerError(Exception e) {
        return OpenAIDialectFailures.isServerErrorOrUnreachable(e);
    }

    /** The error-path counterpart of {@link #captureEnvelope}: the retry-after a 429 carried. */
    @Override
    protected RateLimitInfo extractRateLimitInfo(Exception e) {
        if (!OpenAIDialectFailures.is429(e)) {
            return null;
        }
        return new RateLimitInfo(getModel() != null ? getModel().getId() : null, null, null, null, null, null,
                OpenAIDialectFailures.retryAfter(e), true);
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
}
