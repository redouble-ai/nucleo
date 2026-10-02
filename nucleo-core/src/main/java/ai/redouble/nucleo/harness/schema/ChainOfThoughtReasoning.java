/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;


import java.util.*;

/**
 * Multi-step reasoning that captures sequential problem-solving.
 *
 * <p>Use this for complex decisions that require step-by-step analysis.
 * The LLM will document its approach and enumerate the key steps taken
 * to arrive at the conclusion. Particularly valuable for debugging
 * complex reasoning or when transparency is required.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-21)
 */
public class ChainOfThoughtReasoning implements Reasoning {
    @LLMDescription("What is being asked and what approach will be taken")
    private String approach;
    @LLMRequired
    @LLMDescription("Key steps in solving this problem")
    private List<String> steps;

    public String getApproach() {
        return approach;
    }

    public void setApproach(String approach) {
        this.approach = approach;
    }

    public List<String> getSteps() {
        return steps;
    }

    public void setSteps(List<String> steps) {
        this.steps = steps;
    }

    @Override
    public String getUserFriendlyDescription() {
        StringBuilder sb = new StringBuilder();
        if (approach != null && !approach.isEmpty()) {
            sb.append("Approach: ").append(approach);
        }
        if (steps != null && !steps.isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append("\nSteps: ");
            } else {
                sb.append("Steps: ");
            }
            for (int i = 0; i < steps.size(); i++) {
                if (i > 0) {
                    sb.append(" → ");
                }
                sb.append(steps.get(i));
            }
        }
        return !sb.isEmpty() ? sb.toString() : "No chain of thought provided";
    }
}