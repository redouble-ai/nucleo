/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.builtin;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.schema.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the LLM summarizer's decision chain without dispatching a job: a static summary
 * from the annotation wins outright (no LLM call); content marked {@code llmSafe=false}
 * or past the 200K-char ceiling never reaches the LLM and truncates instead; a
 * summarization job that fails falls back to truncation; and the happy path wraps the
 * job's summary in the standard "[SUMMARY: N chars]" prefix carrying the ORIGINAL length.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-17)
 */
class LLMSummarizerTest {

    private static final Identifiable ROOT = Job.workflow("llm-summarizer-test", "llm-summarizer-test");

    @SuppressWarnings("unused")
    private static final class Annotated {
        @LLMSummarizable(value = "chemical id", staticSummary = "SMILES molecular notation")
        String withStatic;
        @LLMSummarizable(value = "clinical description", llmSafe = false)
        String notLlmSafe;
        @LLMSummarizable("web page content")
        String plain;
    }

    private static LLMSummarizable annotation(String field) {
        try {
            return Annotated.class.getDeclaredField(field).getAnnotation(LLMSummarizable.class);
        }
        catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Summarizer whose job seam is controlled: canned answer, hard failure, or a fail() trap. */
    private static class SeamSummarizer extends LLMSummarizer {
        private final String cannedOrNull;

        SeamSummarizer(String cannedOrNull) {
            super(ROOT);
            this.cannedOrNull = cannedOrNull;
        }

        @Override
        String summarizeViaJob(String text, String hint, SummarySize size) throws Exception {
            if (cannedOrNull == null) {
                throw new IllegalStateException("summarization job failed");
            }
            return cannedOrNull;
        }
    }

    @Test
    void staticSummaryWinsWithoutAnyJob() {
        LLMSummarizer summarizer = new SeamSummarizer(null) {
            @Override
            String summarizeViaJob(String text, String hint, SummarySize size) {
                return fail("a static summary must never spawn a job");
            }
        };
        assertEquals("[SUMMARY: 9000 chars] SMILES molecular notation",
                summarizer.summarize("C".repeat(9_000), annotation("withStatic")));
    }

    @Test
    void notLlmSafeContentNeverReachesTheLLM_itTruncates() {
        LLMSummarizer summarizer = new SeamSummarizer(null) {
            @Override
            String summarizeViaJob(String text, String hint, SummarySize size) {
                return fail("llmSafe=false content would be damaged by paraphrasing and must not be sent");
            }
        };
        String summary = summarizer.summarize("precise clinical wording ".repeat(500), annotation("notLlmSafe"));
        assertTrue(summary.startsWith("[SUMMARY: 12500 chars] precise clinical wording"),
                "the conservative path is truncation of the original text: " + summary.substring(0, 60));
    }

    @Test
    void oversizeContentSkipsTheLLM_andTruncates() {
        LLMSummarizer summarizer = new SeamSummarizer(null) {
            @Override
            String summarizeViaJob(String text, String hint, SummarySize size) {
                return fail("content past the 200K-char ceiling must not be sent to a model");
            }
        };
        String summary = summarizer.summarize("x".repeat(200_001), annotation("plain"));
        assertTrue(summary.startsWith("[SUMMARY: 200001 chars]"), summary.substring(0, 30));
    }

    @Test
    void aFailedJobFallsBackToTruncation() {
        String summary = new SeamSummarizer(null).summarize("word ".repeat(300), annotation("plain"));
        assertTrue(summary.startsWith("[SUMMARY: 1500 chars] word word"),
                "the fallback is the truncating summarizer over the same text: " + summary.substring(0, 40));
    }

    @Test
    void theHappyPathWrapsTheJobsSummaryWithTheOriginalLength() {
        String summary = new SeamSummarizer("a crisp llm summary").summarize("word ".repeat(300), annotation("plain"));
        assertEquals("[SUMMARY: 1500 chars] a crisp llm summary", summary,
                "the prefix carries the ORIGINAL length - the signal that substantial content sits behind it");
    }
}
