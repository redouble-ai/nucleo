/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.bedrock;

import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.tokens.*;

/**
 * The usage a Bedrock Converse response reports, as a ledger. Converse counts the way the
 * Anthropic Messages API does, cache writes and reads on top of the input figure, under AWS
 * field names; the total the model read is their sum. A counter Converse leaves out stays
 * null.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-09)
 */
public class BedrockTokenLedger extends AbstractTokenLedger {

    /**
     * @param spec        the entry the call ran under
     * @param inputTokens Converse's {@code inputTokens}: the input neither written to nor read from the cache
     * @param cacheWrite  {@code cacheWriteInputTokens}, or null when the response carries none
     * @param cacheRead   {@code cacheReadInputTokens}, or null when the response carries none
     * @param output      {@code outputTokens}
     */
    public BedrockTokenLedger(ModelSpec spec, Integer inputTokens, Integer cacheWrite, Integer cacheRead, Integer output) {
        super(spec, total(inputTokens, cacheWrite, cacheRead), cacheRead, cacheWrite, output);
    }

    private static Integer total(Integer inputTokens, Integer cacheWrite, Integer cacheRead) {
        if (inputTokens == null) {
            return null;
        }
        return inputTokens + (cacheWrite != null ? cacheWrite : 0) + (cacheRead != null ? cacheRead : 0);
    }
}
