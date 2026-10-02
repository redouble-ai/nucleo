/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.builtin;

import ai.redouble.nucleo.harness.schema.*;

/**
 * Output from the SummarizationTool.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-01-10)
 */
@LLMDescription("Result of text summarization")
public class SummarizationOutput extends ReasonablePojo<SimpleReasoning>  {
    @LLMRequired
    @LLMDescription("The generated summary")
    private String summary;

    @LLMDescription("Original text length in characters")
    private int originalLength;

    @LLMDescription("Compression ratio (original / summary length)")
    private double compressionRatio;

    @LLMDescription("Number of chunking levels used (1 = direct, 2+ = hierarchical)")
    private int chunkingLevels;

    public SummarizationOutput() {
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public int getOriginalLength() {
        return originalLength;
    }

    public void setOriginalLength(int originalLength) {
        this.originalLength = originalLength;
    }

    public double getCompressionRatio() {
        return compressionRatio;
    }

    public void setCompressionRatio(double compressionRatio) {
        this.compressionRatio = compressionRatio;
    }

    public int getChunkingLevels() {
        return chunkingLevels;
    }

    public void setChunkingLevels(int chunkingLevels) {
        this.chunkingLevels = chunkingLevels;
    }
}
