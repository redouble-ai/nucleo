/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.*;
import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.secrets.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

import java.io.*;

/**
 * The embeddings half of the OpenAI dialect over the framework's own HTTP client, for
 * endpoints that speak it without being OpenAI. The endpoint is the {@code host} part of the
 * {@link OpenAICompatibleClient#SECRET_ID} credential, the API root; this client appends
 * {@code /embeddings}. The vector is asked for at the framework's canonical width; an endpoint
 * that ignores {@code dimensions} answers at its native width and the base class folds it.
 *
 * <p>Failures are judged the way the chat client judges them ({@link OpenAIDialectFailures}):
 * by the status the endpoint answered, read off the typed failure the transport raised, which
 * stays the cause of the {@link IOException} the embeddings template classifies.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
public class OpenAICompatibleEmbeddingsClient extends AbstractEmbeddingsClient {
    static final String EMBEDDINGS_PATH = "/embeddings";
    private OpenAIDialectEndpoint endpoint;
    private String path = EMBEDDINGS_PATH;
    private String apiKey;
    private String apiRoot;

    public OpenAICompatibleEmbeddingsClient() {
    }

    /** An explicit endpoint and key, for a harness that has them in hand. */
    public OpenAICompatibleEmbeddingsClient(String apiRoot, String apiKey) {
        this.apiRoot = apiRoot;
        this.apiKey = apiKey;
    }

    /** Over a prepared transport and the path to post to, for a surface whose URL shape is its own (Azure). */
    OpenAICompatibleEmbeddingsClient(OpenAIDialectEndpoint endpoint, String path, ModelSpec model) {
        this.endpoint = endpoint;
        this.path = path;
        setModel(model);
    }

    private OpenAIDialectEndpoint endpoint() {
        if (endpoint == null) {
            if (apiKey == null || apiRoot == null) {
                Credential credential = Secrets.configured().require(OpenAICompatibleClient.SECRET_ID);
                if (credential.host() == null || credential.host().isBlank()) {
                    throw new UncorrectableRuntimeLLMException("The credential '" + OpenAICompatibleClient.SECRET_ID
                            + "' carries no host; its host part is the API root this client posts to (e.g. https://router.huggingface.co/v1)");
                }
                apiKey = apiKey != null ? apiKey : credential.secret();
                apiRoot = apiRoot != null ? apiRoot : credential.host();
            }
            endpoint = new OpenAIDialectEndpoint(OpenAICompatibleClient.SERVICE_NAME, apiRoot, apiKey);
        }
        return endpoint;
    }

    @Override
    protected RawEmbedding doCalculateEmbedding(String input, EmbeddingPurpose purpose) throws IOException {
        ObjectNode body = NucleoJsonSerializer.createObjectNode();
        body.put("model", model.getWireModelId());
        body.put("input", input);
        body.put("dimensions", EmbeddingsClient.DIMENSIONS);
        String responseJson;
        try {
            responseJson = endpoint().postForReply(path, body.toString()).body();
        }
        catch (LLMReadableCheckedException e) {
            // The template classifies an IOException by walking its causes; the typed failure is the cause
            throw new IOException(OpenAIDialectFailures.describe(e, OpenAICompatibleClient.SERVICE_NAME, model.getId()), e);
        }
        return new RawEmbedding(parseEmbedding(responseJson), parseBilledInput(responseJson));
    }

    /**
     * Reads the vector out of the dialect's embeddings response shape. Package-private so the
     * mapping can be asserted without a transport.
     */
    static float[] parseEmbedding(String responseJson) throws IOException {
        JsonNode embedding = NucleoJsonSerializer.readTree(responseJson).get("data").get(0).get("embedding");
        float[] vector = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            vector[i] = (float) embedding.get(i).asDouble();
        }
        return vector;
    }

    /**
     * The input tokens the endpoint billed, {@code usage.prompt_tokens}; null when a
     * compatible endpoint sends no usage, which the ledger then counts as a call of no
     * stated tokens rather than guessing.
     */
    static Integer parseBilledInput(String responseJson) throws IOException {
        JsonNode usage = NucleoJsonSerializer.readTree(responseJson).get("usage");
        return usage != null && usage.hasNonNull("prompt_tokens") ? usage.get("prompt_tokens").asInt() : null;
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
        return "OPENAI-COMPATIBLE / secret " + OpenAICompatibleClient.SECRET_ID;
    }

    @Override
    protected RateLimitInfo extractRateLimitInfo(Exception e) {
        if (!OpenAIDialectFailures.is429(e)) {
            return null;
        }
        return new RateLimitInfo(model != null ? model.getId() : "unknown", null, null, null, null, null,
                OpenAIDialectFailures.retryAfter(e), true, RateLimitType.CAPACITY);
    }
}
