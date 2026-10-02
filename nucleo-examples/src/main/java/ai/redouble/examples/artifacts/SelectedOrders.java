/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.artifacts;

import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.thinking.*;

/**
 * The selecting agent's answer: a summary in the model's words, and - inherited from
 * {@link ThinkerOutput} - the references of the artifacts it selected, which the runtime
 * resolves to the records the tool produced.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
public class SelectedOrders extends ThinkerOutput<SimpleReasoning> {
    @LLMRequired
    @LLMDescription("One sentence saying which orders were selected and why")
    private String summary;

    public SelectedOrders() {
        setReasoning(new SimpleReasoning());
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }
}
