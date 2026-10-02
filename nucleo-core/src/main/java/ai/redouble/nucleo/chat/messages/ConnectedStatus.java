/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.chat.messages;

/**
 * The CONNECTED frame's payload: a {@link StatusMessage} that also tells the client
 * which durable conversation the just-started turn belongs to. Additive on the wire -
 * {@link WebSocketMessage} unwraps payload properties to the top level, so clients
 * validating only {@code status}/{@code message} keep working and see two extra keys.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-25)
 */
public class ConnectedStatus extends StatusMessage {
    private String conversationId;
    private Long scopeId;

    public ConnectedStatus() {
    }

    public ConnectedStatus(String status, String message, String conversationId, Long scopeId) {
        super(status, message);
        this.conversationId = conversationId;
        this.scopeId = scopeId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public Long getScopeId() {
        return scopeId;
    }

    public void setScopeId(Long scopeId) {
        this.scopeId = scopeId;
    }
}
