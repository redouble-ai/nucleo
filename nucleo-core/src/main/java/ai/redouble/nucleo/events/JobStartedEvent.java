/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.events;

import ai.redouble.nucleo.harness.*;

/**
 * Event published when a job starts executing. Carries the attempt; its message is
 * {@code Job started (attempt <n>)}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-15)
 */
public final class JobStartedEvent extends AbstractJobEvent implements LifecycleEvent {
    private final int attempt;

    public JobStartedEvent(JobSnapshot snapshot, int attempt) {
        super(snapshot);
        this.attempt = attempt;
        setMessage("Job started (attempt " + attempt + ")");
    }

    public int getAttempt() {
        return attempt;
    }
}