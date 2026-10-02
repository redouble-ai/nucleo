/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

/**
 * Strategy for summarizing text fields that exceed the {@link LLMSummarizable} threshold.
 *
 * <p>The Jackson serializer calls this when a field marked with {@code @LLMSummarizable}
 * exceeds its threshold during summarized serialization. Two built-in implementations:
 * <ul>
 *   <li>{@link TruncatingSummarizer} - Fast truncation, no external calls. Default.</li>
 *   <li>{@code ai.redouble.nucleo.tools.builtin.LLMSummarizer} - Spawns SummarizationTool
 *       jobs for high-quality summaries. Only use from orchestrators (Thinkers/Doers),
 *       never from resource-bearing Tools.</li>
 * </ul>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-03)
 */
@FunctionalInterface
public interface Summarizer {
    /**
     * Summarize text that exceeds the annotation threshold.
     *
     * @param text the full text to summarize
     * @param annotation the @LLMSummarizable annotation from the field
     * @return summary string, typically in format "[SUMMARY: X chars] brief..."
     */
    String summarize(String text, LLMSummarizable annotation);
}
