/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm;

import ai.redouble.nucleo.harness.models.*;

/**
 * One embeddings call, recorded on the job like every other model call: the catalog id it
 * was issued against, the input tokens the provider billed, the latency, the verdict, and
 * the vector. An embeddings call is no conversation exchange, so the message-shaped parts
 * of {@link LLMResponse} are null on it and its output token count is zero: a cost ledger,
 * a meter, a span or a usage table reads the same fields off it as off a chat call, which
 * is what puts embeddings spend under the same cap as everything else.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-15)
 */
public class EmbeddingsResponse extends LLMResponse<float[]> {
    private final EmbeddingPurpose purpose;
    private float[] vector;

    public EmbeddingsResponse(ModelSpec model, EmbeddingPurpose purpose) {
        this.purpose = purpose;
        setModel(model.getId());
        setProvider(model.getProviderKey());
    }

    /** Corpus item or live probe, which decides the provider's input type. */
    public EmbeddingPurpose getPurpose() {
        return purpose;
    }

    /** The vector at the width the caller gets; null on a failed call. */
    public float[] getVector() {
        return vector;
    }

    public void setVector(float[] vector) {
        this.vector = vector;
    }

    @Override
    public int getLastOutputTokens() {
        return 0;
    }
}
