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
 * Event published when a job is cancelled by explicit request.
 *
 * <p>Not a {@link FailureEvent} - cancellation is a deliberate decision to stop,
 * not a failure to complete. Carries whether the stop was forced; its message is
 * {@code Job cancelled} or {@code Job forcefully cancelled}. For a job cancelled while still
 * queued, the dispatcher builds the event with the remover's message through the
 * message-taking constructor, never by mutating a built event: {@code Cancelled while
 * queued: <reason>} from a cancel by id, {@code Workflow cancelled: <reason>} from a workflow
 * cancel, {@code Cancelled by shutdown} at shutdown.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-11)
 */
public final class JobCancelled extends AbstractTerminalEvent<Void> {
    private final boolean force;

    public JobCancelled(JobSnapshot snapshot, boolean force, int attempts, List<LLMResponse<?>> llmResponses, Map<String, Object> metadata) {
        super(snapshot, attempts, llmResponses, metadata);
        this.force = force;
        setMessage(force ? "Job forcefully cancelled" : "Job cancelled");
    }

    /**
     * A cancellation whose message the publisher states at construction, as the dispatcher does
     * for a job cancelled while still queued.
     */
    public JobCancelled(JobSnapshot snapshot, boolean force, String message, int attempts, List<LLMResponse<?>> llmResponses, Map<String, Object> metadata) {
        super(snapshot, attempts, llmResponses, metadata);
        this.force = force;
        setMessage(message);
    }

    public boolean force() {
        return force;
    }
}