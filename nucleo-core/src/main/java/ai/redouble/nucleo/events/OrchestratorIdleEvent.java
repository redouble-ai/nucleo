/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.events;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;

import java.util.*;

/**
 * Published when an orchestrator (thinker/doer) finishes its current work and goes idle.
 * Transitions the job to {@link JobState#IDLE}. Carries accumulated LLM responses
 * and metadata for the completed exchange, as given; its message and human message are "Ready".
 *
 * <p>For {@code ReactiveThinker}: published after the response is sent to the user.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-08)
 */
public final class OrchestratorIdleEvent extends AbstractJobEvent implements LifecycleEvent, HumanReadable {
    private final List<LLMResponse<?>> llmResponses;
    private final Map<String, Object> metadata;

    public OrchestratorIdleEvent(JobSnapshot snapshot, List<LLMResponse<?>> llmResponses, Map<String, Object> metadata) {
        super(snapshot);
        this.llmResponses = llmResponses;
        this.metadata = metadata;
        setMessage("Ready");
    }

    @Override
    public String getHumanMessage() {
        return message();
    }

    public List<LLMResponse<?>> getLlmResponses() {
        return llmResponses;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }
}
