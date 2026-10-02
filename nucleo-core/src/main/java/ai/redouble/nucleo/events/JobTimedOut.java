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
 * Event published when a job exceeds its maximum execution time. A {@link FailureEvent} with
 * no error: the budget it exceeded is what it carries, and its message names it in seconds.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-14)
 */
public final class JobTimedOut extends AbstractTerminalEvent<Void> implements FailureEvent {
    private final Duration timeout;

    public JobTimedOut(JobSnapshot snapshot, Duration timeout, int attempts, List<LLMResponse<?>> llmResponses, Map<String, Object> metadata) {
        super(snapshot, attempts, llmResponses, metadata);
        this.timeout = timeout;
        setMessage("Job timed out after " + timeout.toSeconds() + " seconds");
    }

    public Duration getTimeout() {
        return timeout;
    }

    @Override
    public Throwable getError() {
        return null;
    }
}