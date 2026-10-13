/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.anthropic;

import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.tokens.*;

/**
 * The usage an Anthropic Messages response reports, as a ledger. Anthropic counts three
 * additive input figures, the uncached input, the cache writes and the cache reads, so the
 * total the model read is their sum; this class does that sum, which is the one piece of
 * Anthropic's usage shape the rest of the runtime must never know.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-09)
 */
public class AnthropicTokenLedger extends AbstractTokenLedger {

    /**
     * @param spec          the entry the call ran under
     * @param regularInput  Anthropic's {@code input_tokens}: the input neither written to nor read from the cache
     * @param cacheCreation {@code cache_creation_input_tokens}, or null when the response carries none
     * @param cacheRead     {@code cache_read_input_tokens}, or null when the response carries none
     * @param output        {@code output_tokens}, thinking included
     */
    public AnthropicTokenLedger(ModelSpec spec, int regularInput, Integer cacheCreation, Integer cacheRead, int output) {
        super(spec, regularInput + (cacheCreation != null ? cacheCreation : 0) + (cacheRead != null ? cacheRead : 0), cacheRead, cacheCreation, output);
    }
}
