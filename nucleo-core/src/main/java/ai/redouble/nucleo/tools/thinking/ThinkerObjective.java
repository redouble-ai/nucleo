/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.prompt.*;

/**
 * Carrier object that binds a registered {@link Prompt} to the per-invocation
 * {@link ThinkerInput}. A thinker composes one of these when its run starts and puts it in
 * the conversation's main objective; the conversation serializes it as a {@code PojoBlock},
 * so the LLM sees one structured JSON object with a {@code prompt} field (the registered
 * system prompt) and an {@code input} field (the per-invocation data).
 *
 * <p>The two fields stay apart. The Prompt carries the registered, guardrailed,
 * substitutable content; the Input carries the data the LLM reasons about, rendered as the
 * JSON of its fields. A thinker author never assembles the text the model reads.
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
