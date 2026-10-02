/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.prompt.*;

/**
 * Carrier object that binds a registered {@link Prompt} to the per-invocation
 * {@link ThinkerInput}. A thinker's {@code runThinkingLoopInternal} composes one of these
 * and pushes it into the main-objective block list; it is serialized through the existing
 * {@code PojoBlock} path so the LLM sees one structured JSON object with a {@code prompt}
 * field (the registered system prompt) and an {@code input} field (the per-invocation
 * data).
 *
 * <p>Structural purpose: ensures {@code INSTRUCTIONS + input.toLLMString()} concatenation
 * can no longer happen at thinker-author level. The Prompt field carries the registered,
 * guardrailed, substitutable content; the Input field carries data the LLM reasons about.
 * They cannot be fused back into a single {@code String}.
 *
 * <p>Simple POJO per framework convention: no-arg constructor, getters and setters, no
 * builder, no fluent interface.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-21)
 */
public class ThinkerObjective {
    private Prompt prompt;
    private ThinkerInput input;

    public ThinkerObjective() {
    }

    public Prompt getPrompt() {
        return prompt;
    }

    public void setPrompt(Prompt prompt) {
        this.prompt = prompt;
    }

    public ThinkerInput getInput() {
        return input;
    }

    public void setInput(ThinkerInput input) {
        this.input = input;
    }
}
