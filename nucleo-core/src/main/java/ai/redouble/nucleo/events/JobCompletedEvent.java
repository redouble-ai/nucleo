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
 * Event published when a job completes successfully. Carries the result, as an optional
 * through {@link #getResult()} (empty for a null result) and directly through
 * {@link #result()}; its message is {@code Job completed successfully (attempt <n>)}.
 *
 * @param <R> the type of result
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-15)
 */
public final class JobCompletedEvent<R> extends AbstractTerminalEvent<R> implements SuccessEvent {
    private final R result;

    public JobCompletedEvent(JobSnapshot snapshot, R result, int attempt, List<LLMResponse<?>> llmResponses, Map<String, Object> metadata) {
        super(snapshot, attempt, llmResponses, metadata);
        this.result = result;
        setMessage("Job completed successfully (attempt " + attempt + ")");
    }

    @Override
    public Optional<R> getResult() {
        return Optional.ofNullable(result);
    }

    /**
     * Gets the result directly (non-optional).
     * Use this when you know the result is not null.
     *
     * @return the result
     */
    public R result() {
        return result;
    }
}