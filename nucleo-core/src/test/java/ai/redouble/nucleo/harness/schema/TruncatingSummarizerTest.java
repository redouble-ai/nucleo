/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the truncation fallback's contract: a static summary from the annotation wins
 * outright (no truncation), otherwise the text is cut to the per-size character budget
 * on a word boundary, and every summary opens with the "[SUMMARY: N chars]" prefix
 * carrying the ORIGINAL length - the signal that substantial content exists behind it.
 * No annotation means the SHORT budget.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-17)
 */
class TruncatingSummarizerTest {

    @SuppressWarnings("unused")
    private static final class Annotated {
        @LLMSummarizable(value = "chemical id", staticSummary = "SMILES molecular notation")
        String withStatic;
        @LLMSummarizable(value = "web page", size = SummarySize.BRIEF)
        String brief;
        @LLMSummarizable(value = "web page", size = SummarySize.SHORT)
        String shortSize;
        @LLMSummarizable(value = "long document", size = SummarySize.PARAGRAPHS)
        String paragraphs;
    }

    private static LLMSummarizable annotation(String field) {
        try {
            return Annotated.class.getDeclaredField(field).getAnnotation(LLMSummarizable.class);
        }
        catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }

    private final TruncatingSummarizer summarizer = new TruncatingSummarizer();

    @Test
    void staticSummaryWinsOutright_prefixCarriesTheOriginalLength() {
        String text = "C".repeat(12_345);
        assertEquals("[SUMMARY: 12345 chars] SMILES molecular notation",
                summarizer.summarize(text, annotation("withStatic")),
                "a static summary replaces the content whole - nothing of it is quoted");
    }

    @Test
    void textUnderTheBudgetSurvivesWhole() {
        String text = "short enough to keep";
        assertEquals("[SUMMARY: " + text.length() + " chars] " + text,
                summarizer.summarize(text, annotation("shortSize")),
                "under the budget nothing is cut - only the length prefix is added");
    }

    @Test
    void perSizeBudgets_cutOnAWordBoundary() {
        String text = ("word ").repeat(400);
        String brief = summarizer.summarize(text, annotation("brief"));
        String shortSummary = summarizer.summarize(text, annotation("shortSize"));
        String paragraphs = summarizer.summarize(text, annotation("paragraphs"));
        assertTrue(budgetUsed(brief, text) <= 200, "BRIEF cuts at the 200-char budget: " + budgetUsed(brief, text));
        assertTrue(budgetUsed(shortSummary, text) <= 500, "SHORT cuts at the 500-char budget");
        assertTrue(budgetUsed(paragraphs, text) <= 1200, "PARAGRAPHS cuts at the 1200-char budget");
        assertTrue(brief.endsWith("word..."), "the cut lands on a word boundary, never mid-word: " + brief.substring(brief.length() - 12));
        assertTrue(budgetUsed(shortSummary, text) > budgetUsed(brief, text), "a larger size keeps more text");
    }

    @Test
    void noAnnotationMeansTheShortBudget() {
        String text = ("word ").repeat(400);
        assertTrue(budgetUsed(summarizer.summarize(text, null), text) <= 500,
                "a field without the annotation truncates at the SHORT default");
    }

    /** Characters of the original text kept in the summary, prefix excluded. */
    private static int budgetUsed(String summary, String original) {
        String prefix = "[SUMMARY: " + original.length() + " chars] ";
        assertTrue(summary.startsWith(prefix), "every summary opens with the length prefix: " + summary.substring(0, Math.min(summary.length(), 40)));
        String kept = summary.substring(prefix.length());
        return kept.endsWith("...") ? kept.length() - 3 : kept.length();
    }
}
