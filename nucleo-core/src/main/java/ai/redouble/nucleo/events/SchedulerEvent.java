/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.events;

import ai.redouble.nucleo.harness.*;

import java.io.*;
import java.time.*;

/**
 * System-level event for scheduler state changes: started, stopping, stopped, or an error
 * carrying its cause. Each type has a default message a caller may replace; an error event's
 * message names the error's own message, or "Unknown" without one. Not tied to any job, so it
 * answers a null snapshot and reaches global and by-type subscriptions for all jobs, never a
 * job-type-scoped or a workflow-scoped one.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-10-14)
 */
public final class SchedulerEvent implements SystemEvent, Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Types of scheduler events.
     */
    public enum Type {
        STARTED("Job scheduler started"),
        STOPPING("Job scheduler stopping"),
        STOPPED("Job scheduler stopped"),
        ERROR("Scheduler error");

        private final String defaultMessage;

        Type(String defaultMessage) {
            this.defaultMessage = defaultMessage;
        }

        public String getDefaultMessage() {
            return defaultMessage;
        }
    }

    private final Type type;
    private final Throwable error;
    private final Instant timestamp;
    private final String message;

    /**
     * Creates a scheduler event with default message.
     */
    public SchedulerEvent(Type type) {
        this(type, type.getDefaultMessage(), null);
    }

    /**
     * Creates a scheduler event with custom message.
     */
    public SchedulerEvent(Type type, String message) {
        this(type, message, null);
    }

    /**
     * Creates a scheduler error event.
     */
    public SchedulerEvent(Throwable error) {
        this(Type.ERROR,
             "Scheduler error: " + (error != null ? error.getMessage() : "Unknown"),
             error);
    }

    /**
     * Full constructor.
     */
    public SchedulerEvent(Type type, String message, Throwable error) {
        this.type = type;
        this.error = error;
        this.timestamp = Instant.now();
        this.message = message;
    }

    public Type getType() {
        return type;
    }

    public Throwable getError() {
        return error;
    }

    // JobEvent interface implementation

    @Override
    public JobSnapshot snapshot() {
        // System events don't have snapshots
        return null;
    }

    @Override
    public Instant timestamp() {
        return timestamp;
    }

    @Override
    public String message() {
        return message;
    }
}