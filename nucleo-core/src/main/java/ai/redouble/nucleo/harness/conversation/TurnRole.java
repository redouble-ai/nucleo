/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import ai.redouble.nucleo.harness.errors.*;

/**
 * The role of a conversation turn as it goes to the provider. Deliberately two-valued:
 * system content is not a turn. The main objective is the conversation's only system
 * content, carried separately by {@link PreparedConversation}, so a "system turn" is
 * not expressible and no client can route one incorrectly.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-23)
 */
public enum TurnRole {
    USER,
    ASSISTANT;

    /**
     * Maps a message's role string to a turn role. This is the single point where the
     * conversation's string roles become wire roles. Anything other than user or
     * assistant is refused: system content goes through
     * {@code ConversationContext.putMainObjective}, never through a message, and an
     * unrecognized role is a corrupted conversation, not something to silently relabel.
     */
    public static TurnRole of(String role) {
        if ("user".equalsIgnoreCase(role)) {
            return USER;
        }
        if ("assistant".equalsIgnoreCase(role)) {
            return ASSISTANT;
        }
        throw new UncorrectableRuntimeLLMException(
                "Message role '" + role + "' is not a conversation turn. Turns are user or assistant;"
                + " system content goes through the main objective.");
    }
}
