/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.chat.messages;

import com.fasterxml.jackson.annotation.*;

import java.time.*;

/**
 * Generic WebSocket message wrapper for outgoing messages.
 *
 * <p>Provides message envelope (type, timestamp, workflowId) and wraps arbitrary data.
 * Jackson's {@code @JsonUnwrapped} merges data fields into top-level JSON, avoiding nested structure.
 *
 * <p><b>Example JSON output:</b>
 * <pre>{@code
 * {
 *   "type": "stream",
 *   "timestamp": "2025-11-14T...",
 *   "workflowId": "abc-123",
 *   "snapshot": {...},        // From UserFacingEvent
 *   "msgType": "STATUS_UPDATE",
 *   "title": "Processing",
 *   "content": "Step 1 of 5",
 *   "chunk": {...}            // From ContentStreamEvent
 * }
 * }</pre>
 *
 * @param <T> Data type (typically UserFacingEvent or StatusMessage)
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-15)
 */
public class WebSocketMessage<T> {
    private WebSocketMessageType type;
    private Instant timestamp;
    private String workflowId;

    @JsonUnwrapped
    private T data;

    public WebSocketMessage() {
        this.timestamp = Instant.now();
    }

    public WebSocketMessage(WebSocketMessageType type, String workflowId, T data) {
        this.type = type;
        this.timestamp = Instant.now();
        this.workflowId = workflowId;
        this.data = data;
    }

    public static <T> WebSocketMessage<T> create(WebSocketMessageType type, String workflowId, T data) {
        return new WebSocketMessage<>(type, workflowId, data);
    }

    public static WebSocketMessage<Void> simple(WebSocketMessageType type, String workflowId) {
        return new WebSocketMessage<>(type, workflowId, null);
    }

    public WebSocketMessageType getType() {
        return type;
    }

    public void setType(WebSocketMessageType type) {
        this.type = type;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
