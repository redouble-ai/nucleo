/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import ai.redouble.nucleo.tools.thinking.*;

import java.time.*;
import java.util.*;

/**
 * Store for long-term persistence of conversation contexts.
 * Typically backed by database or durable file storage.
 * Supports querying, archival, and version history.
 * <p>
 * Like the base contract, every conversation-addressed operation takes the acting
 * principal - conversations are user-bound and the durable store enforces it.
 * Parts of this surface are planned but not built yet; those implementations throw
 * {@link ai.redouble.nucleo.harness.errors.NotImplementedException} rather than pretending with a
 * no-op or an empty result.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-22)
 */
public interface PersistentStore extends ContextStore {

    /**
     * Creates the durable record for a conversation at its FIRST user message:
     * enrichment - title and searchable embedding, both derived from that message -
     * plus the insert, exactly once per conversation. Every later boundary save rides
     * {@link ContextStore#saveContext} as a plain update with no reads. Implementations
     * must order early updates behind an in-flight creation.
     *
     * @param context the conversation, holding at least its first user utterance
     * @param thinker the minting thinker (provides scope and the acting principal)
     */
    void createContext(ConversationContext context, Thinker<?, ?> thinker);

    /**
     * Query object for finding contexts.
     * Implementations can extend this for more specific queries.
     */
    class ContextQuery {
        private String userIdPattern;
        private String objectivePattern;
        private Instant createdAfter;
        private Instant createdBefore;
        private Integer minMessages;
        private Integer maxMessages;
        private Map<String, String> metadata;
        private Integer limit;
        private Integer offset;

        // Getters and setters
        public String getUserIdPattern() { return userIdPattern; }
        public void setUserIdPattern(String userIdPattern) { this.userIdPattern = userIdPattern; }

        public String getObjectivePattern() { return objectivePattern; }
        public void setObjectivePattern(String objectivePattern) { this.objectivePattern = objectivePattern; }

        public Instant getCreatedAfter() { return createdAfter; }
        public void setCreatedAfter(Instant createdAfter) { this.createdAfter = createdAfter; }

        public Instant getCreatedBefore() { return createdBefore; }
        public void setCreatedBefore(Instant createdBefore) { this.createdBefore = createdBefore; }

        public Integer getMinMessages() { return minMessages; }
        public void setMinMessages(Integer minMessages) { this.minMessages = minMessages; }

        public Integer getMaxMessages() { return maxMessages; }
        public void setMaxMessages(Integer maxMessages) { this.maxMessages = maxMessages; }

        public Map<String, String> getMetadata() { return metadata; }
        public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }

        public Integer getLimit() { return limit; }
        public void setLimit(Integer limit) { this.limit = limit; }

        public Integer getOffset() { return offset; }
        public void setOffset(Integer offset) { this.offset = offset; }
    }

    /**
     * Finds contexts matching the given query, within what the principal owns.
     *
     * @param query the query parameters
     * @param user the acting principal
     * @return list of matching conversation IDs
     */
    List<String> findContexts(ContextQuery query, String user);

    /**
     * Saves a snapshot with metadata for indexing and querying.
     *
     * @param snapshot the conversation snapshot to save
     * @param metadata additional metadata for indexing
     * @param user the acting principal, recorded as the conversation's owner
     */
    void saveWithMetadata(ConversationPersistenceSnapshot snapshot, Map<String, String> metadata, String user);

    /**
     * Gets metadata associated with a context.
     *
     * @param conversationId unique identifier for the context
     * @param user the acting principal
     * @return metadata map, or null if not found
     */
    Map<String, String> getMetadata(String conversationId, String user);

    /**
     * Archives a context, moving it to cold storage.
     * Archived contexts may have slower retrieval times.
     *
     * @param conversationId unique identifier for the context
     * @param user the acting principal
     * @return true if successfully archived, false if not found
     */
    boolean archive(String conversationId, String user);

    /**
     * Restores an archived context back to active storage.
     *
     * @param conversationId unique identifier for the context
     * @param user the acting principal
     * @return true if successfully restored, false if not found or not archived
     */
    boolean restore(String conversationId, String user);

    /**
     * Checks if a context is archived.
     *
     * @param conversationId unique identifier for the context
     * @param user the acting principal
     * @return true if archived, false otherwise
     */
    boolean isArchived(String conversationId, String user);

    /**
     * Gets version history for a context.
     * Some implementations may track all saves as versions.
     *
     * @param conversationId unique identifier for the context
     * @param user the acting principal
     * @return list of version timestamps, most recent first
     */
    List<Instant> getVersionHistory(String conversationId, String user);

    /**
     * Loads a specific version of a context snapshot.
     *
     * @param conversationId unique identifier for the context
     * @param versionTime timestamp of the version to load
     * @param user the acting principal
     * @return the snapshot at that version, or null if not found
     */
    ConversationPersistenceSnapshot loadVersion(String conversationId, Instant versionTime, String user);

    /**
     * Creates a backup of all contexts.
     * Implementation-specific (might be no-op for some stores).
     *
     * @param backupId identifier for this backup
     * @param user the acting principal
     * @return number of contexts backed up
     */
    int backup(String backupId, String user);

    /**
     * Gets storage statistics.
     *
     * @return map of statistics (e.g., "total_contexts", "total_size_bytes", "archived_count")
     */
    Map<String, Object> getStats();
}