/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

import ai.redouble.nucleo.harness.artifacts.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the cut: the text is shortened to the per-size character budget on a word
 * boundary, and every cut opens with the "[SUMMARY: N chars]" prefix carrying the
 * ORIGINAL length - the signal that substantial content exists behind it. A fixed
 * phrase is the serializer's decision and is no part of the cut.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-17)
 */
class TruncatingSummarizerTest {

    @SuppressWarnings("unused")
    @TypeAlias("test:truncating")
    private static final class Annotated extends AbstractArtifact {
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
    void aFixedPhraseIsNotTheCutsDecision_theTextIsCutAsAnyOther() {
        String text = "C".repeat(12_345);
        assertEquals("[SUMMARY: 12345 chars] " + "C".repeat(500) + "...",
                summarizer.summarize(text, annotation("withStatic")),
                "the cut reads the size and nothing else; the serializer writes the fixed phrase before a cut is considered");
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

    /** Characters of the original text kept in the summary, prefix excluded. */
    private static int budgetUsed(String summary, String original) {
        String prefix = "[SUMMARY: " + original.length() + " chars] ";
        assertTrue(summary.startsWith(prefix), "every summary opens with the length prefix: " + summary.substring(0, Math.min(summary.length(), 40)));
        String kept = summary.substring(prefix.length());
        return kept.endsWith("...") ? kept.length() - 3 : kept.length();
    }
}
