/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.events;

import ai.redouble.nucleo.harness.*;

/**
 * Event published when a job is scheduled or queued for execution. The dispatcher fires it
 * with state {@code QUEUED}, the job's requirements and its priority; the one-argument form
 * is acceptance alone, with no requirements and priority zero.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-08-11)
 */
public final class JobScheduled extends AbstractJobEvent implements LifecycleEvent {

    /**
     * State of the scheduled job.
     */
    public enum State {
        ACCEPTED,    // Job accepted into the system
        QUEUED       // Job queued for execution
    }

    private final State state;
    private final JobRequirements requirements;
    private final int priority;

    /**
     * Creates a job scheduled event with state and requirements.
     */
    public JobScheduled(JobSnapshot snapshot, State state, JobRequirements requirements, int priority) {
        super(snapshot);
        this.state = state;
        this.requirements = requirements;
        this.priority = priority;
    }

    /**
     * Creates a job scheduled event for acceptance.
     */
    public JobScheduled(JobSnapshot snapshot) {
        this(snapshot, State.ACCEPTED, null, 0);
    }

    public State getState() {
        return state;
    }

    public JobRequirements requirements() {
        return requirements;
    }

    public int priority() {
        return priority;
    }
}