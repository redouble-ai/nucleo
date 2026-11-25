/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

/**
 * Makes the summary of one text of an {@link LLMSummarizable} field.
 *
 * <p>The serializer decides which fields are summarized and how; a caller never chooses
 * a summarizer for a write. The two implementations are the two ways the serializer has
 * of summarizing a text that names no {@link FieldSummarizer} of its own:
 * <ul>
 *   <li>{@link TruncatingSummarizer} - the cut: the head of the text within the size's
 *       budget, marked with the full length. No external calls.</li>
 *   <li>{@code ai.redouble.nucleo.tools.builtin.LLMSummarizer} - a model's summary, made
 *       by a summarization job when a conversation is sent to a model. It throws when
 *       the job produces no summary.</li>
 * </ul>
 *
 * <p>Summarizing saves tokens and never fails a serialization. When the model's
 * summarizer throws or returns null, the failure is logged, nothing is kept, and the text
 * is shown cut until a later making succeeds.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-03)
 */
@FunctionalInterface
public interface Summarizer extends FieldSummarizer<String> {
    /**
     * Summarize text that exceeds the annotation threshold.
     *
     * @param text the full text to summarize
     * @param annotation the @LLMSummarizable annotation from the field
     * @return summary string, typically in format "[SUMMARY: X chars] brief..."
     */
    @Override
    String summarize(String text, LLMSummarizable annotation);
}
