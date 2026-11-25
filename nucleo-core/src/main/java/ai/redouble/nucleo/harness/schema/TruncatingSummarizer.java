/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

/**
 * The cut: a text shortened to a character budget derived from
 * {@link LLMSummarizable#size()}, at a word boundary, marked with the length of the whole.
 *
 * <p>It is what the serializer shows for a field whose content must not be paraphrased
 * ({@link LLMSummarizable#llmSafe()} false), for a text too long to hand to a model, and
 * for any field whose summary is not made yet. It does only that: the fixed phrase of
 * {@link LLMSummarizable#staticSummary()} is the serializer's decision, made before a
 * cut is considered.
 *
 * <p>Always safe to use from any context (Tools, Thinkers, Doers) - no external calls,
 * no blocking, no resource requirements.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-03)
 */
public class TruncatingSummarizer implements Summarizer {
    @Override
    public String summarize(String text, LLMSummarizable annotation) {
        String truncated = truncateText(text, charBudgetFor(annotation.size()));
        return String.format("[SUMMARY: %d chars] %s", text.length(), truncated);
    }

    /**
     * Character budget per size. Matches the LLM summarizer's expected output length
     * for the same size so the cut and a model's summary are of comparable size.
     */
    private static int charBudgetFor(SummarySize size) {
        return switch (size) {
            case BRIEF -> 200;
            case SHORT -> 500;
            case PARAGRAPHS -> 1200;
        };
    }

    /**
     * Truncates text to a maximum length, preserving word boundaries.
     */
    private String truncateText(String text, int maxLen) {
        if (text.length() <= maxLen) {
            return text;
        }
        int breakPoint = maxLen;
        for (int i = maxLen; i > maxLen - 50 && i > 0; i--) {
            char c = text.charAt(i);
            if (c == ' ' || c == '.' || c == ',' || c == '\n') {
                breakPoint = i;
                break;
            }
        }
        return text.substring(0, breakPoint).trim() + "...";
    }
}
