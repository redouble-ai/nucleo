/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.events.heartbeat;

import ai.redouble.nucleo.harness.*;

import java.time.*;

/**
 * A schedule request re-used an existing heartbeatId: the previous spec was replaced
 * (most-recent-wins). Carries both specs so the audit trail shows exactly what changed; the
 * message is {@code Heartbeat rebound: <id> (was <displaced>, now <current>)}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-29)
 */
public record HeartbeatRebound(Heartbeat displaced, Heartbeat current, Instant timestamp) implements HeartbeatEvent {

    public HeartbeatRebound(Heartbeat displaced, Heartbeat current) {
        this(displaced, current, Instant.now());
    }

    @Override
    public String message() {
        return "Heartbeat rebound: " + current.getHeartbeatId() + " (was " + displaced + ", now " + current + ")";
    }
}
