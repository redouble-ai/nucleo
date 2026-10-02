/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.tools.thinking.*;

/**
 * Base interface for storing and retrieving conversation contexts.
 * Implementations can provide different storage backends and persistence strategies.
 *
 * <p>Conversations are user-bound: every operation that addresses a stored
 * conversation takes the ACTING principal, and implementations enforce ownership
 * under that identity (a durable store's load refuses a principal who does not own
 * the conversation). There is no synthetic system principal - the caller always
 * has a real user in hand, and the contract makes it hand it over.
 *
 * <p>Works with ConversationPersistenceSnapshot for proper serialization/deserialization.
 * When loading, the caller must provide ModelSpec and ResponseHandler to rehydrate messages.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-22)
 */
public interface ContextStore {
    /**
     * Saves a conversation as a snapshot.
     * If a context with this ID already exists, it will be overwritten.
     *
     * @param snapshot the conversation snapshot to save
     * @param user the acting principal, recorded as the conversation's owner
     * @throws IllegalArgumentException if snapshot or its conversationId is null
     * @throws IllegalStateException if the store is not available
     */
    void save(ConversationPersistenceSnapshot snapshot, String user);

    /**
     * Loads a conversation snapshot by its ID, on behalf of the given principal.
     *
     * @param conversationId unique identifier for the context
     * @param user the acting principal; a durable store refuses a principal who
     *             does not own the conversation
     * @param caller the job on whose behalf the read runs: the live orchestrator when
     *               the load happens inside a workflow (a thinker adopting its
     *               conversation), or a fresh workflow root
     *               ({@code Job.workflow(user, "load-conversation")}) when no job exists
     *               (an endpoint serving a request). A store that reads through
     *               dispatched tools parents them under it, so the read lands inside
     *               the workflow that needed it instead of minting a root of its own.
     * @return the loaded snapshot, or null if not found
     * @throws IllegalArgumentException if conversationId is null or empty
     * @throws IllegalStateException if the store is not available
     */
    ConversationPersistenceSnapshot load(String conversationId, String user, Identifiable caller);

    /**
     * Loads and rehydrates a conversation context.
     * Convenience method that loads the snapshot and converts to ConversationContext.
     *
     * @param conversationId unique identifier for the context
     * @param responseHandler the response handler for message rehydration
     * @param user the acting principal
     * @param caller the job on whose behalf the read runs; see {@link #load}
     * @return the rehydrated context, or null if not found
     * @throws IllegalArgumentException if any parameter is null
     * @throws IllegalStateException if the store is not available
     */
    default ConversationContext loadContext(String conversationId, ResponseHandler<?> responseHandler, String user, Identifiable caller) {
        ConversationPersistenceSnapshot snapshot = load(conversationId, user, caller);
        if (snapshot == null) {
            return null;
        }
        return snapshot.toConversation(responseHandler);
    }

    /**
     * Saves a conversation context by converting to snapshot - the boundary save,
     * fired on every user message in and every response out. For a durable store this
     * is a plain update of the record that
     * {@link PersistentStore#createContext} made at the first user message: no
     * existence reads, no re-titling, and a missing row (the user deleted the
     * conversation mid-turn) fails the save loudly rather than resurrecting it.
     *
     * @param context the conversation context to save
     * @param thinker the thinker that owns this conversation (provides domain-specific context)
     * @throws IllegalArgumentException if context is null
     * @throws IllegalStateException if the store is not available
     */
    default void saveContext(ConversationContext context, Thinker<?, ?> thinker) {
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
        save(context.toSnapshot(), thinker != null ? thinker.getUserId() : context.getUserId());
    }

    /**
     * Checks if a context with the given ID exists for the given principal.
     *
     * @param conversationId unique identifier for the context
     * @param user the acting principal
     * @return true if the context exists, false otherwise
     */
    boolean exists(String conversationId, String user);

    /**
     * Deletes a conversation context by its ID, on behalf of the given principal.
     *
     * @param conversationId unique identifier for the context
     * @param user the acting principal
     * @return true if the context was deleted, false if it didn't exist
     * @throws IllegalArgumentException if conversationId is null or empty
     * @throws IllegalStateException if the store is not available
     */
    boolean delete(String conversationId, String user);

    /**
     * Gets the last modification time for a context.
     *
     * @param conversationId unique identifier for the context
     * @param user the acting principal
     * @return timestamp in milliseconds since epoch, or null if not found
     */
    Long getLastModified(String conversationId, String user);
}
