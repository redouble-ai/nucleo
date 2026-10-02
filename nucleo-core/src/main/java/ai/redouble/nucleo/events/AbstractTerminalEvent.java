/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.events;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.llm.*;

import java.time.*;
import java.util.*;

/**
 * Sealed base class for the four terminal events the dispatcher fires for a job it ran:
 * completion, failure, timeout and cancellation. Carries the attempt count, the LLM responses
 * and metadata collected during the run (an empty list or map when none were given, never
 * null), and the instant the terminal state was reached, which is the completion time; the
 * duration runs from the snapshot's start to that instant and is zero for a job that never
 * started. {@link DependencyFailureEvent} is the one terminal event outside this class: it
 * reports a job that never ran, so it carries the interface defaults.
 *
 * @param <R> the type of result this terminal event carries
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-15)
 */
public abstract sealed class AbstractTerminalEvent<R> extends AbstractJobEvent implements TerminalEvent
        permits JobCompletedEvent, JobFailedEvent, JobTimedOut, JobCancelled {

    /**
     * The number of attempts made before reaching this terminal state.
     */
    protected final int attempts;

    /**
     * The LLM responses made during job execution.
     */
    protected final List<LLMResponse<?>> llmResponses;

    /**
     * Job execution metadata (wait times, costs, etc.).
     */
    protected final Map<String, Object> metadata;

    /**
     * The time when the terminal state was reached.
     */
    protected final Instant terminatedAt;

    /**
     * Creates a terminal event from a job snapshot.
     *
     * @param snapshot     the job snapshot
     * @param attempts     the number of attempts
     * @param llmResponses the LLM responses from the job execution
     * @param metadata     the job execution metadata
     */
    protected AbstractTerminalEvent(JobSnapshot snapshot, int attempts, List<LLMResponse<?>> llmResponses, Map<String, Object> metadata) {
        super(snapshot);
        this.attempts = attempts;
        this.llmResponses = llmResponses != null ? llmResponses : List.of();
        this.metadata = metadata != null ? metadata : Map.of();
        this.terminatedAt = Instant.now();
    }

    /**
     * Gets the number of attempts.
     *
     * @return the number of attempts
     */
    public int getAttempts() {
        return attempts;
    }

    /**
     * Gets the time when the terminal state was reached.
     *
     * @return the termination time
     */
    public Instant getTerminatedAt() {
        return terminatedAt;
    }

    @Override
    public List<LLMResponse<?>> getLlmResponses() {
        return llmResponses;
    }

    @Override
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    @Override
    public Instant getStartTime() {
        return snapshot().getStartedAt();
    }

    @Override
    public Instant getCompletionTime() {
        return terminatedAt;
    }

    @Override
    public Duration getDuration() {
        if (snapshot().getStartedAt() != null) {
            return Duration.between(snapshot().getStartedAt(), terminatedAt);
        }
        return Duration.ZERO;
    }

    /**
     * The result, present on a completion event only; empty on every other terminal event.
     *
     * @return optional result
     */
    public Optional<R> getResult() {
        return Optional.empty();
    }

}