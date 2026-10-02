/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import java.time.*;

/**
 * Base interface for all job-related events: a snapshot of the job at the moment of the
 * event, the event's instant, and an optional message. The bus routes by the concrete event
 * class, the snapshot's workflow id and the snapshot's job class.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-08-10)
 */
public interface JobEvent {
    /**
     * Gets the job snapshot containing all job metadata and state.
     * This is an immutable snapshot of the job at the time the event was created.
     *
     * @return the job snapshot
     */
    JobSnapshot snapshot();
    /**
     * Gets the timestamp when this event was created.
     *
     * @return the event creation timestamp
     */
    Instant timestamp();
    /**
     * Gets the message associated with this event.
     * May provide additional context about the state change or event.
     *
     * @return the event message, may be null
     */
    String message();
}