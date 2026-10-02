/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.events.heartbeat;

import ai.redouble.nucleo.harness.*;

import java.time.*;

/**
 * Submission threw when the Heart tried to fire a due heartbeat. For recurring entries
 * the schedule lives on - the next instance is enqueued regardless, so one bad fire
 * never kills a cadence. The message is {@code Heartbeat fire failed: <id> - <failure>}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-29)
 */
public record HeartbeatFireFailed(Heartbeat heartbeat, Exception failure, Instant timestamp) implements HeartbeatEvent {

    public HeartbeatFireFailed(Heartbeat heartbeat, Exception failure) {
        this(heartbeat, failure, Instant.now());
    }

    @Override
    public String message() {
        return "Heartbeat fire failed: " + heartbeat.getHeartbeatId() + " - " + failure;
    }
}
