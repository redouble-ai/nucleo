/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.events.heartbeat;

import ai.redouble.nucleo.harness.*;

import java.time.*;

/**
 * Confirmation that a heartbeat landed in the store - the publisher's way to verify its
 * schedule was accepted (a rebind publishes {@link HeartbeatRebound} instead). The message is
 * {@code Heartbeat scheduled: <heartbeat>}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-29)
 */
public record HeartbeatScheduled(Heartbeat heartbeat, Instant timestamp) implements HeartbeatEvent {

    public HeartbeatScheduled(Heartbeat heartbeat) {
        this(heartbeat, Instant.now());
    }

    @Override
    public String message() {
        return "Heartbeat scheduled: " + heartbeat;
    }
}
