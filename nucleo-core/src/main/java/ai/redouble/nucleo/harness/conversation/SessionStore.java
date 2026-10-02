/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import java.time.*;
import java.util.*;

/**
 * Store for session-scoped conversation contexts with automatic expiration.
 * Typically backed by in-memory or fast cache storage like Redis.
 * Sessions are expected to be short-lived and tied to specific tasks or user sessions.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-22)
 */
public interface SessionStore extends ContextStore {
    /**
     * Saves a snapshot with a time-to-live (TTL).
     * The context will be automatically deleted after the TTL expires.
     *
     * @param snapshot the conversation snapshot to save
     * @param ttl time-to-live duration
     * @throws IllegalArgumentException if snapshot is null
     */
    void saveWithTTL(ConversationPersistenceSnapshot snapshot, Duration ttl);

    /**
     * Sets or updates the TTL for an existing context.
     *
     * @param conversationId unique identifier for the context
     * @param ttl new time-to-live duration
     * @return true if TTL was set, false if context doesn't exist
     */
    boolean setTTL(String conversationId, Duration ttl);

    /**
     * Gets the remaining TTL for a context.
     *
     * @param conversationId unique identifier for the context
     * @return remaining time-to-live, or null if context doesn't exist or has no TTL
     */
    Duration getRemainingTTL(String conversationId);

    /**
     * Refreshes the TTL for a context, resetting it to its original duration.
     * Useful for keeping active sessions alive.
     *
     * @param conversationId unique identifier for the context
     * @return true if TTL was refreshed, false if context doesn't exist
     */
    boolean refresh(String conversationId);

    /**
     * Gets all active session IDs.
     * Useful for monitoring and cleanup operations.
     *
     * @return set of active session context IDs
     */
    Set<String> getActiveSessions();

    /**
     * Gets sessions that match a pattern.
     * Useful for finding related sessions (e.g., all sessions for a user).
     *
     * @param pattern pattern to match (implementation-specific, e.g., "user:123:*")
     * @return set of matching context IDs
     */
    Set<String> findSessions(String pattern);

    /**
     * Clears all expired sessions.
     * Some implementations may do this automatically.
     *
     * @return number of sessions cleared
     */
    int clearExpired();
}