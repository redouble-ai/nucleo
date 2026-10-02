/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

/**
 * Reasoning for tool selection and orchestration decisions.
 *
 * <p>Captures the rationale behind choosing specific tools or actions,
 * including what information is needed and confidence in the selection.
 * This reasoning type is particularly useful for agent systems that need
 * to explain their tool choices.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-21)
 */
public class ToolSelectionReasoning implements Reasoning {
    @LLMRequired
    @LLMDescription("What information or action is needed and why this tool addresses that need")
    private String rationale;
    @LLMDescription("Confidence level in this tool choice: high, medium, or low")
    private String confidence;

    public String getRationale() {
        return rationale;
    }

    public void setRationale(String rationale) {
        this.rationale = rationale;
    }

    public String getConfidence() {
        return confidence;
    }

    public void setConfidence(String confidence) {
        this.confidence = confidence;
    }

    @Override
    public String getUserFriendlyDescription() {
        StringBuilder sb = new StringBuilder();
        if (rationale != null) {
            sb.append(rationale);
        }
        if (confidence != null && !confidence.isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append(" (Confidence: ").append(confidence).append(")");
            } else {
                sb.append("Confidence: ").append(confidence);
            }
        }
        return !sb.isEmpty() ? sb.toString() : "No tool selection reasoning provided";
    }
}