/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.chat;

/**
* One stored conversation as the chat picker lists it. Typed rather than
* assembled as JSON in each controller, so the key names cannot drift between
* modules - the frontend reads the same five fields everywhere.
*
 * @author Andrey Santrosyan
* @since 0.1 (2026-08-17)
*/
public class ChatConversationInfo {

    private Long id;
    private String conversationId;
    private String title;
    private String owner;
    private Long lastUpdated;

    /**
     * Row id of the stored conversation. Present for completeness; the frontend
     * addresses conversations by {@link #getConversationId()}, which is the
     * framework's identity and what the socket accepts.
     */
    public Long getId() {
        return this.id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getConversationId() {
        return this.conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getTitle() {
        return this.title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getOwner() {
        return this.owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    /**
     * Epoch milliseconds of the last message, or null when never updated.
     */
    public Long getLastUpdated() {
        return this.lastUpdated;
    }

    public void setLastUpdated(Long lastUpdated) {
        this.lastUpdated = lastUpdated;
    }
}
