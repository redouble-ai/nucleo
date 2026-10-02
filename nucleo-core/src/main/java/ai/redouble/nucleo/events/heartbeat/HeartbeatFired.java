/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.events.heartbeat;

import ai.redouble.nucleo.harness.*;

import java.time.*;

/**
 * A due heartbeat was dispatched. Carries both the store-entry identity (the heartbeat)
 * and the resulting run's jobId - the only correlation channel between a schedule and
 * its runs. The message is {@code Heartbeat fired: <id> -> job <jobId>}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-29)
 */
public record HeartbeatFired(Heartbeat heartbeat, String jobId, Instant timestamp) implements HeartbeatEvent {

    public HeartbeatFired(Heartbeat heartbeat, String jobId) {
        this(heartbeat, jobId, Instant.now());
    }

    @Override
    public String message() {
        return "Heartbeat fired: " + heartbeat.getHeartbeatId() + " -> job " + jobId;
    }
}
