/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

/**
 * Reasoning for information extraction and relevance assessment.
 *
 * <p>Designed for tasks that involve analyzing documents or data to extract
 * relevant information. Captures why certain information was deemed relevant
 * and acknowledges any limitations or assumptions in the analysis.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-21)
 */
public class AnalysisReasoning implements Reasoning {
    @LLMRequired
    @LLMDescription("Why this information is relevant to the query")
    private String relevanceJustification;
    @LLMDescription("Any assumptions or limitations in this analysis")
    private String caveats;

    public String getRelevanceJustification() {
        return relevanceJustification;
    }

    public void setRelevanceJustification(String relevanceJustification) {
        this.relevanceJustification = relevanceJustification;
    }

    public String getCaveats() {
        return caveats;
    }

    public void setCaveats(String caveats) {
        this.caveats = caveats;
    }

    @Override
    public String getUserFriendlyDescription() {
        StringBuilder sb = new StringBuilder();
        if (relevanceJustification != null && !relevanceJustification.isEmpty()) {
            sb.append("Relevance: ").append(relevanceJustification);
        }
        if (caveats != null && !caveats.isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append("\nCaveats: ");
            } else {
                sb.append("Caveats: ");
            }
            sb.append(caveats);
        }
        return !sb.isEmpty() ? sb.toString() : "No analysis reasoning provided";
    }
}