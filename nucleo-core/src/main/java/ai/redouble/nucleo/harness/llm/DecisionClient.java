/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm;

import ai.redouble.nucleo.harness.decision.*;

import java.io.*;

/**
 * A decision model behind one call: a state and typed questions in, a probability over the
 * options the caller declared per question out, with the call's own accounting (model,
 * billed input tokens, latency) on the response, which is what the job records and the cost
 * ledger prices. The third client family beside {@link LLMClient} and
 * {@link EmbeddingsClient}: it generates no text, holds no conversation and calls no tool.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public interface DecisionClient extends Client {

    /**
     * Answers every question of the request about its state, in one round trip.
     *
     * @throws IOException if the endpoint could not be reached or answered with something that is not an answer
     * @throws InterruptedException if interrupted while waiting
     */
    DecisionResponse decide(DecisionRequest request) throws IOException, InterruptedException;
}
