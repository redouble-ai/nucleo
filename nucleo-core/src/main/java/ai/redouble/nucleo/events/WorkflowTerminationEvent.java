/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.events;

import ai.redouble.nucleo.harness.*;

/**
 * Sealed category for events signaling that an entire workflow has ended.
 *
 * <p>Parallel to {@link LifecycleEvent} rather than nested under it: workflow
 * termination is a workflow-scope cleanup signal, not a job state transition.
 * The root job's own {@link JobCompletedEvent} / {@link JobFailedEvent} covers
 * persistence of the terminal job state; this category exists so
 * workflow-scoped observers (WebSocket connections, UI spinners) can unsubscribe
 * and clean up without waiting for an inactivity timeout. {@link WorkflowCompleteEvent}, the
 * one permitted member, carries the outcome and the reason.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-10-11)
 */
public sealed interface WorkflowTerminationEvent extends JobEvent
        permits WorkflowCompleteEvent {

    /**
     * The reason for workflow termination: one of the two questions every member of this
     * category answers.
     *
     * @return a human-readable description of why the workflow terminated
     */
    String getTerminationReason();

    /**
     * Whether the workflow completed successfully: the other question every member of this
     * category answers.
     *
     * @return true if the workflow completed all its tasks successfully,
     *         false if it was terminated due to error or cancellation
     */
    boolean isSuccessful();
}