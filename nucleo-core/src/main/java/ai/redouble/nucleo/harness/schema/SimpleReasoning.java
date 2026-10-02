/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

import com.fasterxml.jackson.annotation.*;

/**
 * Minimal reasoning implementation for simple justifications.
 *
 * <p>Use this reasoning type when you only need a basic explanation or
 * justification without structured analysis. Suitable for straightforward
 * decisions where a single thought captures the rationale. Its description is the
 * thought itself, so with no thought it is null: an empty one surfaces nothing to a
 * person, where the structured reasonings say in words that nothing was filled in.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-21)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SimpleReasoning implements Reasoning {
    @LLMDescription("What the answer rests on - the inputs used and the steps taken - stated so a reader can check them")
    private String thought;

    public String getThought() {
        return thought;
    }

    public void setThought(String thought) {
        this.thought = thought;
    }

    @Override
    public String getUserFriendlyDescription() {
        return thought;
    }
}