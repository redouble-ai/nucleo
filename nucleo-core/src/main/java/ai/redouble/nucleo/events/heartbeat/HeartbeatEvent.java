/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.events.heartbeat;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.harness.*;

/**
 * Sealed category for heartbeat control-plane events: schedule requests, cancellations,
 * and the Heart's lifecycle notifications. Deliberately NOT {@link OperationalEvent}:
 * that category is high-volume telemetry that broad subscribers must skip, while these
 * are low-volume commands and audit-worthy transitions that dashboards and persisters
 * subscribe to.
 *
 * <p>Every member answers a null {@link JobEvent#snapshot()}: a heartbeat transition has
 * no owning job (the fired job's own lifecycle events carry its snapshot), and the
 * {@code HeartbeatFired} correlation is by jobId. An event with no snapshot reaches global and
 * by-type subscriptions for all jobs, never a job-type-scoped or a workflow-scoped one.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-29)
 */
public sealed interface HeartbeatEvent extends JobEvent
        permits HeartbeatRequested, CancelHeartbeat, HeartbeatScheduled, HeartbeatFired, HeartbeatFireFailed, HeartbeatRebound {

    @Override
    default JobSnapshot snapshot() {
        return null;
    }
}
