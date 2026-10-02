/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.epo;

import ai.redouble.nucleo.harness.schema.*;

/**
 * Output from EPO patent description/specification retrieval.
 * Contains the patent number for joining with a PatentArtifact, plus the raw
 * description text. Does not create a separate artifact.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-02)
 */
public class EPODescriptionOutput {
    @LLMDescription("Patent number this description belongs to")
    private String patentNumber;
    @LLMSummarizable(value = "patent specification", threshold = 2000, size = SummarySize.PARAGRAPHS)
    @LLMDescription("Full description/specification text")
    private String description;
    public EPODescriptionOutput() {
    }
    public String getPatentNumber() {
        return patentNumber;
    }
    public void setPatentNumber(String patentNumber) {
        this.patentNumber = patentNumber;
    }
    public String getDescription() {
        return description;
    }
    public void setDescription(String description) {
        this.description = description;
    }
}
