/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.*;
import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.secrets.*;
import com.openai.client.*;
import com.openai.client.okhttp.*;
import com.openai.errors.*;
import com.openai.models.embeddings.*;

import java.util.*;

/**
 * OpenAI's embedding models through OpenAI's own client. The vector is asked for at the
 * framework's canonical width ({@code dimensions}): the text-embedding-3 family supports
 * Matryoshka reduction, so the configured width is the one source of truth and no client-side
 * fold is needed. Retries are the framework's ({@code maxRetries(0)}).
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
public class OpenAISDKEmbeddingsClient extends AbstractEmbeddingsClient {
    private OpenAIClient client;
    private String apiKey;

    public OpenAISDKEmbeddingsClient() {
    }

    /** An explicit key, for a harness that has it in hand. */
    public OpenAISDKEmbeddingsClient(String apiKey) {
        this.apiKey = apiKey;
    }

    /** The SDK client, built on first use; overridable to supply a recording one. */
    protected OpenAIClient client() {
        if (client == null) {
            if (apiKey == null) {
                apiKey = Secrets.configured().require(OpenAIProvider.SECRET_ID).secret();
            }
            client = OpenAIOkHttpClient.builder().apiKey(apiKey).maxRetries(0).build();
        }
        return client;
    }

    @Override
    protected RawEmbedding doCalculateEmbedding(String input, EmbeddingPurpose purpose) {
        CreateEmbeddingResponse response = client().embeddings().create(EmbeddingCreateParams.builder()
                .model(model.getWireModelId())
                .input(input)
                .dimensions(EmbeddingsClient.DIMENSIONS)
                .build());
        return new RawEmbedding(toVector(response), (int) response.usage().promptTokens());
    }

    /** The first vector of the response as floats. Package-private so the mapping can be asserted without a transport. */
    static float[] toVector(CreateEmbeddingResponse response) {
        List<Float> values = response.data().get(0).embedding();
        float[] vector = new float[values.size()];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = values.get(i);
        }
        return vector;
    }

    @Override
    protected boolean is429Error(Exception e) {
        return OpenAISDKFailures.is429(e);
    }

    @Override
    protected boolean isQuotaError(Exception e) {
        return OpenAISDKFailures.isQuota(e);
    }

    @Override
    protected boolean isServerError(Exception e) {
        return OpenAISDKFailures.isServerError(e);
    }

    @Override
    protected String accountIdentifier() {
        return OpenAIProvider.ACCOUNT;
    }

    @Override
    protected RateLimitInfo extractRateLimitInfo(Exception e) {
        OpenAIServiceException svc = OpenAISDKFailures.serviceException(e);
        if (svc == null) {
            return null;
        }
        List<String> tokensLimit = svc.headers().values("x-ratelimit-limit-tokens");
        List<String> requestsLimit = svc.headers().values("x-ratelimit-limit-requests");
        return new RateLimitInfo(model != null ? model.getId() : "unknown",
                RateLimitInfo.parseIntOrNull(tokensLimit.isEmpty() ? null : tokensLimit.get(0)), null, null,
                RateLimitInfo.parseIntOrNull(requestsLimit.isEmpty() ? null : requestsLimit.get(0)), null, null,
                svc instanceof RateLimitException, RateLimitType.CAPACITY);
    }
}
