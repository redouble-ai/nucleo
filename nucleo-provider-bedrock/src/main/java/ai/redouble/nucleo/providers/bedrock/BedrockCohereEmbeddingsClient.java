/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.bedrock;

import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import software.amazon.awssdk.core.*;
import software.amazon.awssdk.core.exception.*;
import software.amazon.awssdk.services.bedrockruntime.*;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.io.*;
import java.util.*;

/**
 * Cohere Embed client on AWS Bedrock InvokeModel. Speaks the Cohere embed request body
 * (texts / input_type / embedding_types / output_dimension), mapping {@link EmbeddingPurpose}
 * onto Cohere's asymmetric input types so corpus and query vectors land in the same space.
 *
 * <p>The spec must pin its native output dimension ({@code embedding_dimensions} in the
 * catalog): the point of this endpoint is producing vectors at the schema's native width
 * with no expand/fold loss, so a spec without the pin is a configuration error that fails
 * loud at client construction. The response vector length is verified against the requested
 * dimension before it is returned, so a provider-side dimension surprise can never reach a
 * vector column silently.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-10)
 */
public class BedrockCohereEmbeddingsClient extends AbstractEmbeddingsClient {
    private final BedrockRuntimeClient bedrockClient;

    public BedrockCohereEmbeddingsClient() {
        try {
            this.bedrockClient = BedrockClients.newRuntimeClient();
        }
        catch (Exception e) {
            throw new RuntimeException("Failed to initialize Bedrock client", e);
        }
    }

    /** For same-package tests exercising the wire format and classifiers without AWS credentials. */
    BedrockCohereEmbeddingsClient(BedrockRuntimeClient bedrockClient) {
        this.bedrockClient = bedrockClient;
    }

    @Override
    public void setModel(ModelSpec model) {
        super.setModel(model);
        if (model.getEmbeddingDimensions() == null) {
            throw new UncorrectableRuntimeLLMException("Model '" + model.getId()
                    + "' has no embedding_dimensions; BedrockCohereEmbeddingsClient requires the spec to pin the native output dimension");
        }
    }

    /** The header on which Bedrock reports the input tokens it billed for an InvokeModel call. */
    static final String INPUT_TOKEN_COUNT_HEADER = "X-Amzn-Bedrock-Input-Token-Count";

    @Override
    protected RawEmbedding doCalculateEmbedding(String input, EmbeddingPurpose purpose) throws IOException, InterruptedException {
        int outputDimension = model.getEmbeddingDimensions();
        String responseJson;
        Integer inputTokens;
        try {
            InvokeModelResponse response = bedrockClient.invokeModel(InvokeModelRequest.builder()
                    .modelId(model.getWireModelId())
                    .contentType("application/json")
                    .accept("application/json")
                    .body(SdkBytes.fromUtf8String(buildRequestBody(input, purpose, outputDimension)))
                    .build());
            responseJson = response.body().asUtf8String();
            inputTokens = response.sdkHttpResponse().firstMatchingHeader(INPUT_TOKEN_COUNT_HEADER).map(Integer::valueOf).orElse(null);
        }
        catch (AbortedException e) {
            // the SDK signals thread interruption as AbortedException; surface it as the
            // interrupt it is instead of letting the blanket wrap misclassify it as transient
            Thread.currentThread().interrupt();
            InterruptedException interrupted = new InterruptedException(e.getMessage());
            interrupted.initCause(e);
            throw interrupted;
        }
        catch (SdkException e) {
            // an unresolvable endpoint hostname means the model is not served in this
            // deployment's region, because the region built the hostname; say that
            RuntimeException refined = BedrockClients.refineUnknownHost(e, model, BedrockClients.region());
            if (refined != e) {
                throw refined;
            }
            // the template classifies 429/5xx only for IOException, so SDK failures must
            // cross the boundary as one, with the typed exception preserved in the chain
            throw new IOException(e);
        }
        float[] vector = parseEmbedding(responseJson);
        if (vector.length != outputDimension) {
            throw new IOException("Cohere embed returned a " + vector.length + "-dim vector where "
                    + outputDimension + " was requested for model '" + model.getId() + "'");
        }
        return new RawEmbedding(vector, inputTokens);
    }

    static String buildRequestBody(String input, EmbeddingPurpose purpose, int outputDimension) {
        ObjectNode body = NucleoJsonSerializer.createObjectNode();
        body.putArray("texts").add(input);
        body.put("input_type", switch (purpose) {
            case DOCUMENT -> "search_document";
            case QUERY -> "search_query";
        });
        body.putArray("embedding_types").add("float");
        body.put("output_dimension", outputDimension);
        // NONE = deterministic error on overflow rather than silent truncation, which would
        // degrade retrieval invisibly; callers cap input length upstream
        body.put("truncate", "NONE");
        return body.toString();
    }

    /**
     * Extracts the single requested vector from either documented response shape:
     * {@code embeddings} as an array of vectors ({@code embeddings_floats}) or as an
     * object keyed by embedding type ({@code embeddings_by_type}).
     */
    static float[] parseEmbedding(String responseJson) throws IOException {
        JsonNode resp = NucleoJsonSerializer.readTree(responseJson);
        JsonNode embeddings = resp.get("embeddings");
        JsonNode vectors = null;
        if (embeddings != null && embeddings.isArray()) {
            vectors = embeddings;
        }
        else if (embeddings != null && embeddings.isObject()) {
            vectors = embeddings.get("float");
        }
        if (vectors == null || !vectors.isArray() || vectors.isEmpty()) {
            List<String> keys = new ArrayList<>();
            resp.fieldNames().forEachRemaining(keys::add);
            throw new IOException("Unrecognized Cohere embed response shape: response_type="
                    + resp.path("response_type").asText("absent") + ", keys=" + keys);
        }
        JsonNode vector = vectors.get(0);
        float[] ret = new float[vector.size()];
        for (int i = 0; i < vector.size(); i++) {
            ret[i] = (float) vector.get(i).asDouble();
        }
        return ret;
    }

    @Override
    protected boolean is429Error(Exception e) {
        return BedrockClients.isThrottling(e);
    }

    @Override
    protected boolean isServerError(Exception e) {
        return BedrockClients.isServerError(e);
    }

    @Override
    protected RateLimitInfo extractRateLimitInfo(Exception e) {
        return new RateLimitInfo(
                model != null ? model.getId() : "unknown",
                null, null, null, null, null, null,
                true, RateLimitType.CAPACITY);
    }
}
