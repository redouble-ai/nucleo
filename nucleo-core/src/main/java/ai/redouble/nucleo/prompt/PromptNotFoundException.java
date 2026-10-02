/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.prompt;

import ai.redouble.nucleo.harness.errors.*;

/**
 * Thrown by {@link Prompts#produce(String)} when the requested key resolves to no source
 * in the per-key override, global backend, or default layers. The LLM cannot correct this
 * by retrying with different parameters; the deployment is mis-configured or the caller
 * asked for a key that was never registered.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-20)
 */
public class PromptNotFoundException extends UncorrectableLLMException {
    private final String key;

    public PromptNotFoundException(String key) {
        super("No PromptSource registered for key: " + key);
        this.key = key;
    }

    public String getKey() {
        return key;
    }

    @Override
    public String getLLMMessage() {
        return "Prompt key '" + key + "' is not registered. Check the @StaticPrompt / " +
               "@DynamicPrompt declaration exists and the classpath scan reached its package.";
    }
}
