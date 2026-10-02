/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.events;

import ai.redouble.nucleo.harness.*;

/**
 * Event published when a job fails because a job it depended on failed, before it ran. Its
 * message names the error's class and message, or {@code Unknown} without an error; a
 * {@link FailureEvent} outside
 * {@link AbstractTerminalEvent}, so it carries the interface defaults: no responses, no
 * metadata, timing from the snapshot.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-22)
 */
public final class DependencyFailureEvent extends AbstractJobEvent implements FailureEvent {
    private final Throwable error;

    public DependencyFailureEvent(JobSnapshot snapshot, Throwable error) {
        super(snapshot);
        this.error = error;
        setMessage("Job failed due to dependency failure: " +
            (error != null ? error.getClass().getName() + ": " + error.getMessage() : "Unknown"));
    }

    @Override
    public Throwable getError() {
        return error;
    }
}