/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.events;

/**
 * Sealed marker for job failures: exceptions, timeouts, and dependency failures.
 *
 * <p>{@link JobCancelled} is intentionally excluded - cancellation is an explicit decision
 * to stop, not a failure to complete.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-15)
 */
public sealed interface FailureEvent extends TerminalEvent
        permits JobFailedEvent, JobTimedOut, DependencyFailureEvent {
    /**
     * Gets the error that caused the failure.
     * @return the error, may be null
     */
    Throwable getError();
}