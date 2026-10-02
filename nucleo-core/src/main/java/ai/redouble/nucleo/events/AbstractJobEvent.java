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
 * Abstract base class for rich job events.
 * Provides common functionality for event classes that need more than record simplicity.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-15)
 */
public abstract class AbstractJobEvent implements JobEvent, Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final JobSnapshot snapshot;
    private final Instant timestamp;
    private String message;

    /**
     * Constructor from JobSnapshot.
     * @param snapshot the job snapshot (can be null for system events)
     */
    protected AbstractJobEvent(JobSnapshot snapshot) {
        this.snapshot = snapshot;
        this.timestamp = Instant.now();
    }

    @Override
    public JobSnapshot snapshot() {
        return snapshot;
    }

    @Override
    public Instant timestamp() {
        return timestamp;
    }

    @Override
    public String message() {
        return message;
    }

    /** For the subclasses' constructors: an event is immutable once built. */
    protected void setMessage(String message) {
        this.message = message;
    }
}