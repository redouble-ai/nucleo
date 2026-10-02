/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.chat.messages;

/**
 * Simple status/error message for WebSocket responses.
 *
 * <p>Used for connection status, errors, and simple notifications.
 * Wrapped in {@link WebSocketMessage} for transmission.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-10-11)
 */
public class StatusMessage {
    private String status;
    private String message;

    public StatusMessage() {
    }

    public StatusMessage(String status, String message) {
        this.status = status;
        this.message = message;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}