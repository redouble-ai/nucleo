/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.harness.schema.*;

/**
 * Output from a dynamically spawned sub-agent.
 *
 * <p>Contains the sub-agent's text summary plus inherited artifact references
 * and structured reasoning. Typed artifacts from tool calls are resolved
 * automatically by {@code SingleObjectiveThinker.resolveArtifacts()}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-03-29)
 */
public class SubThinkerOutput extends ThinkerOutput<AnalysisReasoning> {
    @LLMRequired
    @LLMDescription("Summary of findings and conclusions")
    private String summary;

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }
}
