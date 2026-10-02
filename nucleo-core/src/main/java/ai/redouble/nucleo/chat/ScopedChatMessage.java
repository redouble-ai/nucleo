/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.chat;

/**
* What the browser sends over a scoped chat socket. One message type serves
* every application: {@code scopeId} names whatever the conversation is bound
* to - a project, a ticket, a case - and the host's endpoint knows what that
* id resolves to.
* <p>
* Message types: {@code message}, {@code load_conversation}, {@code ping},
* {@code force_unlock}.
*
 * @author Andrey Santrosyan
* @since 0.1 (2026-08-17)
*/
public class ScopedChatMessage {

    private String type;
    private String content;
    private Long scopeId;
    private String conversationId;
    private String reason;

    public String getType() {
        return this.type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getContent() {
        return this.content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    /**
     * The entity this conversation is bound to. Required on the first
     * {@code message} of a socket; ignored afterwards, since a socket stays bound
     * to the scope it opened with.
     */
    public Long getScopeId() {
        return this.scopeId;
    }

    public void setScopeId(Long scopeId) {
        this.scopeId = scopeId;
    }

    public String getConversationId() {
        return this.conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getReason() {
        return this.reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
