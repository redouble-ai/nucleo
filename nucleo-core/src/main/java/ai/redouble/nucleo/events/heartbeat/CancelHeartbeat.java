/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.events.heartbeat;

import java.time.*;

/**
 * A request to remove a pending heartbeat by id. Open-publish by design: cancellation
 * is denial-shaped, not escalation-shaped - a wrongly cancelled schedule is diagnosable
 * from the bus history and the store, and re-scheduling requires authority. Removes the
 * whole entry; an in-flight fired job is untouched (job cancellation is the existing
 * separate mechanism). A null or blank id is refused with {@link IllegalArgumentException}:
 * there is no cancel-all. The message is {@code Heartbeat cancel requested: <id>}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-29)
 */
public record CancelHeartbeat(String heartbeatId, Instant timestamp) implements HeartbeatEvent {

    public CancelHeartbeat(String heartbeatId) {
        this(heartbeatId, Instant.now());
    }

    public CancelHeartbeat {
        if (heartbeatId == null || heartbeatId.isBlank()) {
            throw new IllegalArgumentException("CancelHeartbeat requires the heartbeatId - there is no cancel-all");
        }
    }

    @Override
    public String message() {
        return "Heartbeat cancel requested: " + heartbeatId;
    }
}
