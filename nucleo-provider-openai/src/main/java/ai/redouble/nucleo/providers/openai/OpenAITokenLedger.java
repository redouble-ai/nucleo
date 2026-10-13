/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.tokens.*;

/**
 * The usage an OpenAI response reports, as a ledger. OpenAI's prompt count is already the
 * total the model read, and its cached count is the part of that total served from the
 * cache; OpenAI bills no cache writes, so that facet is zero, a fact and not an absence.
 * This is the one piece of OpenAI's usage shape the rest of the runtime must never know.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-09)
 */
public class OpenAITokenLedger extends AbstractTokenLedger {

    /**
     * @param spec             the entry the call ran under
     * @param promptTokens     {@code prompt_tokens} or {@code input_tokens}: the whole input, cached part included
     * @param cachedTokens     the cached part of the input, or null when the response carries none
     * @param completionTokens {@code completion_tokens} or {@code output_tokens}, reasoning included
     */
    public OpenAITokenLedger(ModelSpec spec, int promptTokens, Integer cachedTokens, int completionTokens) {
        super(spec, promptTokens, cachedTokens, 0, completionTokens);
    }
}
