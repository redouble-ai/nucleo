/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.chat.messages;

import com.fasterxml.jackson.annotation.*;

/**
 * Base class for incoming WebSocket messages with polymorphic deserialization.
 * Jackson will automatically deserialize to the correct subtype based on the "type" field.
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type"
)
@JsonSubTypes({
    @JsonSubTypes.Type(value = IncomingMessage.ChatMessageRequest.class, name = "message"),
    @JsonSubTypes.Type(value = IncomingMessage.PingRequest.class, name = "ping"),
    @JsonSubTypes.Type(value = IncomingMessage.SubscribeRequest.class, name = "subscribe"),
    @JsonSubTypes.Type(value = IncomingMessage.UnsubscribeRequest.class, name = "unsubscribe"),
    @JsonSubTypes.Type(value = IncomingMessage.ListRequest.class, name = "list"),
    @JsonSubTypes.Type(value = IncomingMessage.ForceUnlockRequest.class, name = "force_unlock")
})
/**
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-21)
 */
public abstract class IncomingMessage {

    /**
     * Chat message request.
     */
    public static class ChatMessageRequest extends IncomingMessage {
        private String content;
        private String workflowId;

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public String getWorkflowId() {
            return workflowId;
        }

        public void setWorkflowId(String workflowId) {
            this.workflowId = workflowId;
        }
    }

    /**
     * Ping request for keepalive.
     */
    public static class PingRequest extends IncomingMessage {
        // No additional fields needed
    }

    /**
     * Subscribe to workflow progress.
     */
    public static class SubscribeRequest extends IncomingMessage {
        private String workflowId;

        public String getWorkflowId() {
            return workflowId;
        }

        public void setWorkflowId(String workflowId) {
            this.workflowId = workflowId;
        }
    }

    /**
     * Unsubscribe from workflow progress.
     */
    public static class UnsubscribeRequest extends IncomingMessage {
        private String workflowId;

        public String getWorkflowId() {
            return workflowId;
        }

        public void setWorkflowId(String workflowId) {
            this.workflowId = workflowId;
        }
    }

    /**
     * List current subscriptions.
     */
    public static class ListRequest extends IncomingMessage {
        // No additional fields needed
    }

    /**
     * Force unlock a conversation that's locked by another job.
     * This will cancel the owning job if possible.
     */
    public static class ForceUnlockRequest extends IncomingMessage {
        private String reason;  // Optional reason for force unlock

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }
}