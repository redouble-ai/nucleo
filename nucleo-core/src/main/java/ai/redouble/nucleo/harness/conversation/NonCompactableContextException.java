/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import ai.redouble.nucleo.harness.errors.*;

/**
 * Thrown when a conversation that does not fit the model's context window is marked
 * non-compactable ({@link ConversationContext#setCompactable}), so the runtime may not
 * summarize anything to make it fit. A different failure from
 * {@link ContextOverflowException}, which says compaction was tried and was not enough: here
 * compaction was never allowed, and the owner of the conversation decided that.
 *
 * <p>Uncorrectable: the model cannot shrink its own history by retrying. The caller starts a
 * new conversation, reduces what it carries, or lifts the flag.
 *
 * <p><strong>LLM sees:</strong></p>
 * <pre>
 * Conversation context is too large and this conversation may not be compacted. Start a new
 * conversation or reduce the amount of context.
 * [This error is not correctable - consider an alternative approach]
 * </pre>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-12)
 */
public class NonCompactableContextException extends UncorrectableLLMException {
    private final int estimatedTokens;
    private final int maxContextTokens;

    /**
     * @param estimatedTokens  the input the conversation would send, as the model estimates it
     * @param maxContextTokens the model's context window
     * @param modelId          the catalog id of the model the conversation runs on
     */
    public NonCompactableContextException(int estimatedTokens, int maxContextTokens, String modelId) {
        super(String.format("Conversation is marked non-compactable and its %,d-token input does not fit %s's window of %,d tokens; nothing may be summarized",
                estimatedTokens, modelId, maxContextTokens));
        this.estimatedTokens = estimatedTokens;
        this.maxContextTokens = maxContextTokens;
    }

    /** The input the conversation would send, as the model estimates it. */
    public int getEstimatedTokens() {
        return estimatedTokens;
    }

    /** The model's context window. */
    public int getMaxContextTokens() {
        return maxContextTokens;
    }

    @Override
    public String getLLMMessage() {
        return "Conversation context is too large and this conversation may not be compacted. "
                + "Start a new conversation or reduce the amount of context.";
    }
}
