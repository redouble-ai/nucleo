/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;


/**
 * Holds both summary and full text for a summarized artifact field.
 *
 * <p>Used in the cached serialization map where long text fields are replaced
 * with this tuple. The summary is shown in LLM context while the full text
 * remains available for search tools and get_artifact_field retrieval.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-01-10)
 */
public class SummarizedField {
    private String summary;
    private String fullText;

    public SummarizedField() {
    }

    public SummarizedField(String fullText, String summary) {
        this.fullText = fullText;
        this.summary = summary;
    }

    /**
     * Gets the summary with length prefix.
     * Format: "[SUMMARY: X chars] Brief summary..."
     */
    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    /**
     * Gets the original full text content.
     */
    public String getFullText() {
        return fullText;
    }

    public void setFullText(String fullText) {
        this.fullText = fullText;
    }

    /**
     * Gets the length of the original full text.
     */
    public int getOriginalLength() {
        return fullText != null ? fullText.length() : 0;
    }
}
