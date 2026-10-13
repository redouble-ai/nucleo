/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import java.time.*;

/**
 * Base interface for all messages in a conversation.
 * This is a minimal interface that allows ConversationContext to store
 * both OutgoingMessage and IncomingMessage instances in a single list.
 *
 * <p>A message does not count its own tokens. What a conversation costs a model is the
 * model's estimate of the whole rendered conversation ({@code ModelSpec.estimate}), and the
 * text a model reads for one message is {@code ModelText.render}; {@link #getRawContent()}
 * is the short placeholder form for logs and persistence.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-10-23)
 */
public interface Message {
    /**
     * Gets the role of the message sender. Conversation turns are user or assistant,
     * nothing else - {@link TurnRole#of} refuses any other role string at render, and
     * system content goes through the main objective, never through a message.
     */
    String getRole();
    /**
     * Gets the raw text content of the message.
     */
    String getRawContent();
    /**
     * Gets the unique message ID.
     */
    String getMessageId();
    /**
     * Sets the unique message ID.
     */
    void setMessageId(String messageId);
    /**
     * Gets the timestamp when this message was created.
     */
    Instant getTimestamp();
    /**
     * Sets the timestamp when this message was created.
     */
    void setTimestamp(Instant timestamp);
    /**
     * Checks if this message should be cached by the LLM provider.
     */
    boolean isEnableCache();
    /**
     * Sets whether this message should be cached by the LLM provider.
     */
    void setCache(boolean enableCache);

    /**
     * Checks if this message can be compacted during context window management.
     * Non-compactable messages (like user inputs and final answers) are preserved verbatim.
     *
     * @return true if this message can be compacted, false to preserve verbatim
     */
    default boolean isCompactable() {
        return true;
    }

    /**
     * Sets whether this message can be compacted.
     * Set to false for user messages and final assistant answers that should be preserved.
     *
     * @param compactable true if compactable, false to preserve verbatim
     */
    default void setCompactable(boolean compactable) {
        // Default implementation does nothing - concrete classes override
    }

    /**
     * Checks if this message is application-authored context riding the user
     * channel - a scoped preamble a thinker injects at conversation
     * initialization. Such a message carries the user role for the API but is
     * not a human utterance: creation-time enrichment
     * ({@link ConversationContext#firstUserUtterance()}) skips it.
     *
     * @return true if the application authored this message, false for content a human sent
     */
    default boolean isAppAuthored() {
        return false;
    }

    /**
     * Marks this message as application-authored context on the user channel.
     *
     * @param appAuthored true when the application, not a human, authored this message
     */
    default void setAppAuthored(boolean appAuthored) {
        // Default implementation does nothing - concrete classes override
    }

    /**
     * The per-call output override this message carries, the first layer of
     * {@code ConversationContext.resolveOutputBudget()}.
     *
     * @return the override, or null when the message declares none and the conversation's
     *         or the binding's declaration decides
     */
    default Integer getRequestedOutputTokens() {
        return null;
    }
}