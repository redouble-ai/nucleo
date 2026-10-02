/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.registry;

import ai.redouble.nucleo.harness.schema.*;

import java.util.*;

/**
 * Input for the {@code request_skill} meta-tool. The LLM passes one or more skill names;
 * {@code ToolHub} finds each in the thinker's reconciled skill catalog and admits the ones
 * that pass the registered admission guardrails.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-21)
 */
public class RequestSkillInput {
    @LLMRequired
    @LLMDescription("Names of skills to admit into the conversation")
    private List<String> skillNames;

    public List<String> getSkillNames() {
        return skillNames;
    }

    public void setSkillNames(List<String> skillNames) {
        this.skillNames = skillNames;
    }
}
