/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import ai.redouble.nucleo.guardrails.*;
import java.time.*;

/**
 * A stored heartbeat: the intent of a {@link ScheduleHeartbeat} plus the captured
 * authority under which it will fire - the publisher's principal and, for
 * orchestrator-published schedules, the publisher's sealed {@link ScopeGuard}, replayed
 * through the submission door at fire time so a self-scheduling flow cannot strip its
 * inherited scope.
 *
 * <p>A final class rather than a record ON PURPOSE: a public record cannot hide its
 * canonical constructor, and this constructor staying package-private is the whole
 * security story - application code cannot mint one, so the two framework mints (the
 * publish capture and the dispatcher's authority-bearing entry) are the only doors.
 * A future durable store implementation needs a deliberate rehydration seam, not a
 * widened constructor.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-29)
 */
public final class Heartbeat {
    private final Class<? extends Job<?>> jobClass;
    private final String heartbeatId;
    private final String conversationId;
    private final Instant runAt;
    private final Object input;
    private final Recurrence recurrence;
    private final String userId;
    private final ScopeGuard scopeGuard;

    Heartbeat(ScheduleHeartbeat spec, Instant runAt, String userId, ScopeGuard scopeGuard) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("A heartbeat fires under a real principal - userId is mandatory");
        }
        this.jobClass = spec.jobClass();
        this.heartbeatId = spec.heartbeatId();
        this.conversationId = spec.conversationId();
        this.runAt = runAt;
        this.input = spec.input();
        this.recurrence = spec.recurrence();
        this.userId = userId;
        this.scopeGuard = scopeGuard;
    }

    private Heartbeat(Heartbeat prior, Instant runAt) {
        this.jobClass = prior.jobClass;
        this.heartbeatId = prior.heartbeatId;
        this.conversationId = prior.conversationId;
        this.runAt = runAt;
        this.input = prior.input;
        this.recurrence = prior.recurrence;
        this.userId = prior.userId;
        this.scopeGuard = prior.scopeGuard;
    }

    /** The recurrence's next instance: same spec, same captured authority, shifted runAt. Package-private - only the Heart re-enqueues. */
    Heartbeat next(Instant runAt) {
        return new Heartbeat(this, runAt);
    }

    public Class<? extends Job<?>> getJobClass() {
        return jobClass;
    }

    public String getHeartbeatId() {
        return heartbeatId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public Instant getRunAt() {
        return runAt;
    }

    public Object getInput() {
        return input;
    }

    public Recurrence getRecurrence() {
        return recurrence;
    }

    public String getUserId() {
        return userId;
    }

    public ScopeGuard getScopeGuard() {
        return scopeGuard;
    }

    @Override
    public String toString() {
        return "Heartbeat[" + heartbeatId + " -> " + jobClass.getSimpleName() + " at " + runAt
                + (recurrence != null ? " recurring" : "") + " as " + userId + "]";
    }
}
