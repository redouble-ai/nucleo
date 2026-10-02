/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.builtin;

import ai.redouble.nucleo.harness.schema.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guards for the {@link SummarizationTool} character-counting spiral. A
 * precise length count in the prompt is what triggered Haiku 4.5's thinking budget to
 * spend thousands of tokens enumerating characters one at a time; the fix renders
 * {@link SummarySize} as a prose phrase and routes the numeric cap through
 * {@code max_tokens} instead. These tests pin the invariant "no digit reaches the
 * prompt" for every {@link SummarySize}, and pin the truncation fallback's per-size
 * char budget.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-05-22)
 */
public class SummarizationToolPromptTest {

    @Test
    public void sizeToPhraseHasNoDigits() {
        for (SummarySize size : SummarySize.values()) {
            String phrase = SummarizationTool.sizeToPhrase(size);
            assertNotNull(phrase, "phrase null for " + size);
            assertFalse(phrase.isEmpty(), "phrase empty for " + size);
            assertFalse(phrase.matches(".*\\d.*"),
                "phrase contains a digit (this is the bug the fix prevents) for "
                + size + ": " + phrase);
        }
    }

    @Test
    public void sizeToBackstopTokensIsPositive() {
        for (SummarySize size : SummarySize.values()) {
            int backstop = SummarizationTool.sizeToBackstopTokens(size);
            assertTrue(backstop > 0, "backstop non-positive for " + size + ": " + backstop);
        }
    }

    @Test
    public void backstopGrowsWithSize() {
        int brief = SummarizationTool.sizeToBackstopTokens(SummarySize.BRIEF);
        int shortB = SummarizationTool.sizeToBackstopTokens(SummarySize.SHORT);
        int paragraphs = SummarizationTool.sizeToBackstopTokens(SummarySize.PARAGRAPHS);
        assertTrue(brief < shortB, "BRIEF backstop should be < SHORT");
        assertTrue(shortB < paragraphs, "SHORT backstop should be < PARAGRAPHS");
    }

    @Test
    public void truncatingSummarizerCharBudgetGrowsWithSize() {
        TruncatingSummarizer summarizer = new TruncatingSummarizer();
        String longText = "x".repeat(5000);
        int briefLen = summarizer.summarize(longText, makeAnnotation(SummarySize.BRIEF)).length();
        int shortLen = summarizer.summarize(longText, makeAnnotation(SummarySize.SHORT)).length();
        int paragraphsLen = summarizer.summarize(longText, makeAnnotation(SummarySize.PARAGRAPHS)).length();
        assertTrue(briefLen < shortLen, "BRIEF truncation should produce less than SHORT");
        assertTrue(shortLen < paragraphsLen, "SHORT truncation should produce less than PARAGRAPHS");
    }

    @Test
    public void truncatingSummarizerDefaultsToShortWhenAnnotationNull() {
        TruncatingSummarizer summarizer = new TruncatingSummarizer();
        String longText = "x".repeat(5000);
        String resultNull = summarizer.summarize(longText, null);
        String resultShort = summarizer.summarize(longText, makeAnnotation(SummarySize.SHORT));
        assertEquals(resultShort.length(), resultNull.length(),
            "null annotation should behave the same as SummarySize.SHORT");
    }

    /**
     * Builds a synthetic {@link LLMSummarizable} carrying just a {@link SummarySize},
     * used to drive {@link TruncatingSummarizer} budget-derivation tests.
     */
    private static LLMSummarizable makeAnnotation(SummarySize size) {
        return new LLMSummarizable() {
            @Override public Class<? extends java.lang.annotation.Annotation> annotationType() { return LLMSummarizable.class; }
            @Override public String value() { return "test"; }
            @Override public int threshold() { return 1; }
            @Override public String staticSummary() { return ""; }
            @Override public boolean llmSafe() { return true; }
            @Override public boolean preSummarized() { return false; }
            @Override public SummarySize size() { return size; }
        };
    }
}
