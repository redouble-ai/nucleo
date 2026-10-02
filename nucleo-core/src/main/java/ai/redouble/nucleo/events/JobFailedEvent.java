/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.events;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.llm.*;

import java.util.*;

/**
 * Event published when a job fails with an exception (not timeout, not cancellation). Carries
 * the error, read through {@link #getError()} and the terminal {@link #getCause()}; its message
 * is {@code Job failed: <error message>}, or {@code Job failed: unknown error} without one.
 *
 * <p>This is the technical failure event, published for ALL job failures regardless
 * of whether the job is a root or sub-job, for the observers that persist or log
 * failure details.</p>
 *
 * <p>Distinct from {@link WorkflowCompleteEvent} which is a user-facing terminal
 * signal published only for root jobs.</p>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-03-14)
 * @see JobTimedOut
 * @see JobCancelled
 * @see WorkflowCompleteEvent
 */
public final class JobFailedEvent extends AbstractTerminalEvent<Void> implements FailureEvent {
    private final Throwable error;

    public JobFailedEvent(JobSnapshot snapshot, Throwable error, int attempts,
                          List<LLMResponse<?>> llmResponses, Map<String, Object> metadata) {
        super(snapshot, attempts, llmResponses, metadata);
        this.error = error;
        setMessage("Job failed: " + (error != null ? error.getMessage() : "unknown error"));
    }

    @Override
    public Throwable getError() {
        return error;
    }
}
