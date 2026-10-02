/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.chat.messages;

import com.fasterxml.jackson.annotation.*;

/**
 * Types of outgoing WebSocket messages.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-03-13)
 */
public enum WebSocketMessageType {
    STREAM("stream"),
    ERROR("error"),
    NOTIFICATION("notification"),
    STATUS("status"),
    PROGRESS("progress"),
    CONNECTED("connected"),
    DISCONNECTED("disconnected"),
    PONG("pong"),
    UNLOCKED("unlocked"),
    INFO("info"),
    MESSAGE("message"),
    SUBSCRIPTIONS("subscriptions"),
    CHAT_CLOSED("chat_closed"),
    CONVERSATION_LOADED("conversation_loaded"),
    HISTORY_MESSAGE("history_message"),
    HISTORY_COMPLETE("history_complete"),
    WORKFLOW_COMPLETE("workflow_complete"),
    WORKFLOW_FAILED("workflow_failed"),
    MESSAGE_COMPLETE("message_complete"),
    MESSAGE_FAILED("message_failed"),
    AGENT_PROCESSING("agent_processing"),
    AGENT_IDLE("agent_idle");

    private final String value;

    WebSocketMessageType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
