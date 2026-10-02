/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.events;

import ai.redouble.nucleo.harness.llm.*;

import java.time.*;
import java.util.*;

/**
 * Sealed interface for events representing a job's final state.
 *
 * <p>Permitted subtypes form a closed hierarchy:
 * <ul>
 *   <li>{@link AbstractTerminalEvent} (sealed base) - {@link JobCompletedEvent}, {@link JobFailedEvent},
 *       {@link JobTimedOut}, and {@link JobCancelled}, the explicit stop that is neither success nor failure</li>
 *   <li>{@link SuccessEvent} (sealed) - {@link JobCompletedEvent}</li>
 *   <li>{@link FailureEvent} (sealed) - {@link JobFailedEvent}, {@link JobTimedOut},
 *       {@link DependencyFailureEvent}</li>
 * </ul>
 *
 * <p>All concrete implementations are final. User code cannot create custom terminal
 * events - only the platform fires these. The defaults below are what a terminal event
 * outside {@link AbstractTerminalEvent} answers: no responses, no metadata, timing read off
 * the snapshot, the event's message as the error message, "Unknown" as the error type, and
 * the cause taken from {@link FailureEvent#getError()} on a failure and empty otherwise.
 *
 * <p>Distinct from {@link WorkflowTerminationEvent}, which is a UI signal for
 * workflow-scoped observers and does not extend this interface.
 *
 * @see WorkflowTerminationEvent
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-15)
 */
public sealed interface TerminalEvent extends LifecycleEvent
        permits AbstractTerminalEvent, SuccessEvent, FailureEvent {
    /**
     * Gets the LLM responses made during job execution.
     * @return list of LLM responses, may be empty but never null
     */
    default List<LLMResponse<?>> getLlmResponses() {
        // Default implementation returns empty list
        // Concrete implementations should override to provide actual responses
        return List.of();
    }

    /**
     * Gets the job execution metadata (wait times, costs, etc.).
     * @return metadata map, may be empty but never null
     */
    default Map<String, Object> getMetadata() {
        return Map.of();
    }

    /**
     * Gets the start time of the job.
     * @return start time, or null if not started
     */
    default Instant getStartTime() {
        return snapshot().getStartedAt();
    }

    /**
     * Gets the completion time of the job.
     * @return completion time
     */
    default Instant getCompletionTime() {
        return snapshot().getCompletedAt() != null ? snapshot().getCompletedAt() : Instant.now();
    }

    /**
     * Gets the duration of the job execution.
     * @return duration
     */
    default Duration getDuration() {
        if (snapshot().getStartedAt() != null && snapshot().getCompletedAt() != null) {
            return Duration.between(snapshot().getStartedAt(), snapshot().getCompletedAt());
        }
        return Duration.ZERO;
    }

    /**
     * Gets the error message if this is a failure event.
     * @return error message
     */
    default String getErrorMessage() {
        return message();
    }

    /**
     * Gets the error type if this is a failure event.
     * @return error type
     */
    default String getErrorType() {
        return "Unknown";
    }

    /**
     * Gets the cause if this is a failure event.
     * @return optional cause
     */
    default Optional<Throwable> getCause() {
        if (this instanceof FailureEvent) {
            return Optional.ofNullable(((FailureEvent) this).getError());
        }
        return Optional.empty();
    }
}