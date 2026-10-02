/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

/**
 * States a job can be in during its lifecycle.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-15)
 */
public enum JobState {
    /**
     * Job has been submitted and is waiting in the queue.
     */
    QUEUED,
    /**
     * Job has been picked up by the scheduler and assigned to a worker.
     */
    SCHEDULED,
    /**
     * Job is actively executing.
     */
    RUNNING,
    /**
     * Job is in the process of being cancelled.
     */
    CANCELLING,
    /**
     * Job was cancelled before completion.
     */
    CANCELLED,
    /**
     * Job completed successfully.
     */
    COMPLETED,
    /**
     * Job failed with an error.
     */
    FAILED,
    /**
     * Job exceeded its maximum execution time.
     */
    TIMED_OUT,
    /**
     * Orchestrator finished current work and is waiting for more input.
     * Semi-terminal: not actively running, but may resume. Used by ReactiveThinker
     * between message exchanges.
     */
    IDLE;

    /**
     * Checks if this state represents a terminal state (job is done).
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == TIMED_OUT;
    }

    /**
     * Checks if this state represents successful completion.
     */
    public boolean isSuccess() {
        return this == COMPLETED;
    }

    /**
     * Checks if this state represents any kind of failure.
     */
    public boolean isFailure() {
        return this == FAILED || this == TIMED_OUT;
    }

    /**
     * Checks if the job is currently active (not terminal).
     */
    public boolean isActive() {
        return !isTerminal();
    }
}