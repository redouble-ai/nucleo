/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.events.heartbeat;

import ai.redouble.nucleo.harness.*;

import java.time.*;

/**
 * A framework-minted heartbeat on its way to the store. Constructing one requires a
 * {@link Heartbeat}, which application code cannot mint - the capture paths are the
 * only sources, so this event's presence on the bus already implies captured authority.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-29)
 */
public record HeartbeatRequested(Heartbeat heartbeat, Instant timestamp) implements HeartbeatEvent {

    public HeartbeatRequested(Heartbeat heartbeat) {
        this(heartbeat, Instant.now());
    }

    public HeartbeatRequested {
        if (heartbeat == null) {
            throw new IllegalArgumentException("HeartbeatRequested requires the heartbeat");
        }
    }

    @Override
    public String message() {
        return "Heartbeat requested: " + heartbeat;
    }
}
