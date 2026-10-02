/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm;

import ai.redouble.nucleo.harness.models.*;

/**
 * Common root of every API client the framework constructs, so a single
 * {@link ClientProvider#createClient(ModelSpec)} entry point can return either family.
 * Carries the shape shared by LLM and embeddings clients: the bound {@link ModelSpec}.
 * Capability-specific calls live on {@link LLMClient} / {@link EmbeddingsClient}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-21)
 */
public interface Client {
    ModelSpec getModel();

    void setModel(ModelSpec model);
}
