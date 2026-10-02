/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

/**
 * Default summarizer that truncates text to a character budget derived from the
 * {@link LLMSummarizable#size()} annotation, preserving word boundaries.
 *
 * <p>Checks {@link LLMSummarizable#staticSummary()} first - if set, uses that as the
 * summary label. Otherwise truncates the text content.
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
        int length = text.length();
        if (annotation != null && annotation.staticSummary() != null && !annotation.staticSummary().isEmpty()) {
            return String.format("[SUMMARY: %d chars] %s", length, annotation.staticSummary());
        }
        SummarySize size = annotation != null ? annotation.size() : SummarySize.SHORT;
        String truncated = truncateText(text, charBudgetFor(size));
        return String.format("[SUMMARY: %d chars] %s", length, truncated);
    }

    /**
     * Character budget per size. Matches the LLM summarizer's expected output length
     * for the same size so the truncation fallback and the LLM happy path produce
     * comparably sized output.
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
