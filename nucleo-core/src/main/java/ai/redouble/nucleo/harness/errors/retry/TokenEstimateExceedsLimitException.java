/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors.retry;

import ai.redouble.nucleo.harness.errors.*;

/**
 * Thrown when our pre-flight token estimate exceeds the model's context window.
 *
 * <p>This is uncorrectable - the LLM cannot fix this by changing tool parameters.
 * The conversation itself is too large. The framework handles this by attempting
 * compaction (see SingleObjectiveThinker), and if that fails, wrapping in
 * {@link ai.redouble.nucleo.harness.conversation.ContextOverflowException}.</p>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-12-02)
 */
public class TokenEstimateExceedsLimitException extends UncorrectableLLMException {
    private final int estimatedTokens;
    private final int modelLimit;
    private final String modelName;

    public TokenEstimateExceedsLimitException(int estimatedTokens, int modelLimit, String modelName) {
        super(buildMessage(estimatedTokens, modelLimit, modelName));
        this.estimatedTokens = estimatedTokens;
        this.modelLimit = modelLimit;
        this.modelName = modelName;
    }

    private static String buildMessage(int estimated, int limit, String model) {
        return String.format(
            "Pre-flight ESTIMATE: %,d tokens exceeds %s limit of %,d tokens. " +
            "This is an estimate, not an API response.",
            estimated, model, limit
        );
    }

    @Override
    public String getLLMMessage() {
        return String.format(
            "Conversation context (%,d tokens) exceeds %s limit of %,d tokens. " +
            "Context must be reduced before retrying.",
            estimatedTokens, modelName, modelLimit
        );
    }

    public int getEstimatedTokens() {
        return estimatedTokens;
    }

    public int getModelLimit() {
        return modelLimit;
    }

    public String getModelName() {
        return modelName;
    }

    /**
     * Returns how much over the limit we estimate to be.
     */
    public int getOverflowAmount() {
        return estimatedTokens - modelLimit;
    }

    /**
     * Returns the ratio of estimated tokens to limit (e.g., 1.5 = 50% over).
     */
    public double getOverflowRatio() {
        return (double) estimatedTokens / modelLimit;
    }
}
