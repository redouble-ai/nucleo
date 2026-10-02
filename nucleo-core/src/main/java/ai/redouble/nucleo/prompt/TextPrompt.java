/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.prompt;

import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.databind.*;

/**
 * Baseline {@link Prompt} implementation. A record of {@code (key, content)}; the
 * {@link #context()} is computed from those two components (pure function of key + content).
 *
 * <p>Instances are produced by {@link Prompts#buildPrompt(String, JsonNode)}, which is the
 * only sanctioned construction path. The public constructor exists so Jackson can deserialize;
 * direct callers should use the facade.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-20)
 */
public record TextPrompt(String key, JsonNode content) implements Prompt {
    @Override
    @JsonIgnore
    public PromptContext context() {
        return Prompts.computeContextFor(key, content);
    }
}
