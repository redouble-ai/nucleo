/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

/**
 * A tool that acts on the conversation it was called from. The thinker hands its live
 * conversation over before submitting the tool, the same way it hands the artifact registry
 * to an {@code ArtifactRegistryAware} tool, so the tool never has to obtain the conversation
 * itself: the thinker already owns it for the duration of the turn, and a second obtain from
 * a job the thinker is waiting on would wait for the thinker to release it.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-11)
 */
public interface ConversationAware {
    /** Called by the thinker before the tool runs, with the conversation of the current turn. */
    void setConversation(ConversationContext conversation);

    ConversationContext getConversation();
}
