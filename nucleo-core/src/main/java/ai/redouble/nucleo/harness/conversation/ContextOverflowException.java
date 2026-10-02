/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import ai.redouble.nucleo.harness.errors.*;

/**
 * Exception thrown when conversation context exceeds model limits even after compaction.
 *
 * <p>This is an uncorrectable exception - the LLM cannot reduce context size by retrying
 * with different parameters. The conversation must be started fresh or the user must
 * reduce the amount of context manually.</p>
 *
 * <p><strong>When this occurs:</strong></p>
 * <ul>
 *   <li>Conversation history exceeds model's context window</li>
 *   <li>Aggressive compaction has already been attempted</li>
 *   <li>Non-compactable messages (user input, final answers) prevent further reduction</li>
 * </ul>
 *
 * <p><strong>LLM sees:</strong></p>
 * <pre>
 * Conversation context is too large even after compaction. Start a new conversation
 * or reduce the amount of context.
 * [This error is not correctable - consider an alternative approach]
 * </pre>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-15)
 */
public class ContextOverflowException extends UncorrectableLLMException {

    /**
     * Constructs a context overflow exception.
     *
     * @param message technical description for logging
     */
    public ContextOverflowException(String message) {
        super(message);
    }

    /**
     * Constructs a context overflow exception with cause.
     *
     * @param message technical description for logging
     * @param cause the underlying TokenEstimateExceedsLimitException
     */
    public ContextOverflowException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public String getLLMMessage() {
        return "Conversation context is too large even after compaction. " +
               "Start a new conversation or reduce the amount of context.";
    }
}
