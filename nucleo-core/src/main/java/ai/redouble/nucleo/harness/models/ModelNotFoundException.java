/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import ai.redouble.nucleo.harness.errors.*;

/**
 * Thrown when a model id does not resolve to any catalog spec. The id comes from
 * deployment configuration or a provider response, never from the LLM, so this is an
 * uncorrectable configuration error - unchecked, matching the prior behavior of the
 * removed {@code ModelInfo.fromName} (which threw {@code IllegalArgumentException}).
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-21)
 */
public class ModelNotFoundException extends UncorrectableRuntimeLLMException {
    private final String id;

    public ModelNotFoundException(String id) {
        super("No model spec registered for id: " + id);
        this.id = id;
    }

    public String getId() {
        return id;
    }
}
