/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.thinking.*;
import java.util.*;

/**
 * What the demo agent returns: the answer, and which skills it admitted on the way, so the
 * caller can see the skill door being used. A thinker's output carries its reasoning; this
 * one keeps it simple.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-11)
 */
public class DemoAnswer extends ThinkerOutput<SimpleReasoning> {
    @LLMRequired
    @LLMDescription("The answer to the query")
    private String answer;
    @LLMDescription("Names of the skills admitted into this conversation while answering, in the order they were admitted; empty when none was")
    private List<String> skillsUsed;

    public DemoAnswer() {
        super();
        setReasoning(new SimpleReasoning());
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public List<String> getSkillsUsed() {
        return skillsUsed;
    }

    public void setSkillsUsed(List<String> skillsUsed) {
        this.skillsUsed = skillsUsed;
    }
}
