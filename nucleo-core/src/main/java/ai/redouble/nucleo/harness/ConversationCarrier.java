/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;


/**
 * The claim of conversation continuity: an implementor ADOPTS the conversation identity
 * it is handed - its execution hydrates and continues that conversation. Implementing
 * this interface IS the declaration of that behavior, the same posture as the scope
 * markers ({@code Scoped}): a type opts in by contract, never by a coincidentally-named
 * setter, so a job that merely stores the string can never silently swallow a
 * heartbeat's continuity obligation.
 *
 * <p>The Heart's fire path delivers {@code Heartbeat.conversationId} ONLY through this
 * interface; a heartbeat naming a conversation whose job class does not implement it
 * fails the fire loudly. Nucleo itself has no idea what a conversation is - it delivers
 * identity, and the layer that owns conversations (the thinker substrate's adoption
 * machinery) owns hydration.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-29)
 */
public interface ConversationCarrier {

    /**
     * Adopts an existing conversation identity: the implementor's execution resumes
     * this conversation.
     */
    void setConversationId(String conversationId);
}
