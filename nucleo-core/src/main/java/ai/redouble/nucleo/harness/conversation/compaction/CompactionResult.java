/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation.compaction;

import ai.redouble.nucleo.harness.schema.*;

/**
 * POJO for structured compaction results from LLM.
 * The LLM returns this as JSON with the compacted/summarized content.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-19)
 */
public class CompactionResult  {

    @LLMRequired
    @LLMDescription("The summarized/compacted content of the message(s)")
    private String summary;

    /**
     * Default constructor required for JSON deserialization.
     */
    public CompactionResult() {
    }

    /**
     * Gets the main summary.
     *
     * @return the summarized content
     */
    public String getSummary() {
        return summary;
    }

    /**
     * Sets the main summary.
     *
     * @param summary the summarized content
     */
    public void setSummary(String summary) {
        this.summary = summary;
    }

    @Override
    public String toString() {
        return "CompactionResult{" +
               "summary='" + (summary != null ? summary.substring(0, Math.min(summary.length(), 50)) + "..." : "null") + '\'' +
               '}';
    }
}
