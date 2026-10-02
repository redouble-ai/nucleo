/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm;

import java.io.*;

/**
 * An embeddings model behind one call: text in, a vector at the canonical width out, with
 * the call's own accounting (model, billed input tokens, latency) on the response, which
 * is what the job records and the cost ledger prices.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-07-18)
 */
public interface EmbeddingsClient extends Client {

    /**
     * The canonical embedding vector width. Every client coerces its provider-native output
     * to this dimension (unless the model's spec pins its own), so database vector columns
     * stay one fixed size regardless of provider. A frozen contract: stored vectors are
     * this wide, so changing it is a schema migration and a re-embedding, never a knob flip.
     */
    int DIMENSIONS = 1024;

    /**
     * Embeds one text. The vector on the response is canonical-dimension and L2-normalized
     * whatever the provider; the response also carries what the provider billed for it.
     *
     * @param input the text to embed
     * @param purpose whether this is a stored corpus item or a live search probe
     * @throws IOException if the API call fails
     * @throws InterruptedException if interrupted while waiting
     */
    EmbeddingsResponse embed(String input, EmbeddingPurpose purpose) throws IOException, InterruptedException;

    /** The vector alone, for a caller that has no use for the accounting. The same call. */
    default float[] calculateEmbedding(String input, EmbeddingPurpose purpose) throws IOException, InterruptedException {
        return embed(input, purpose).getVector();
    }
}
