/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.builtin;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.schema.*;
import org.junit.jupiter.api.*;

import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the model step of a summary without dispatching a job: the summarizer hands the
 * text, the annotation's hint and its size to the job and wraps the job's summary in the
 * "[SUMMARY: N chars]" prefix carrying the ORIGINAL length; a job that fails or a wait
 * that is interrupted is thrown, with no fallback of its own; and the serializer, which
 * decides whether a model is asked at all, never hands it a text longer than
 * {@link NucleoJsonSerializer#MODEL_SUMMARY_MAX_CHARS} and cuts when the job fails.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-17)
 */
class LLMSummarizerTest {

    private static final Identifiable ROOT = Job.workflow("llm-summarizer-test", "llm-summarizer-test");

    @TypeAlias("test:llmsummarizer")
    public static final class Page extends AbstractArtifact {
        @LLMSummarizable(value = "web page content", size = SummarySize.BRIEF, threshold = 100)
        private String plain;

        public String getPlain() { return plain; }
        public void setPlain(String plain) { this.plain = plain; }
    }

    private static LLMSummarizable annotation() throws Exception {
        return Page.class.getDeclaredField("plain").getAnnotation(LLMSummarizable.class);
    }

    /** Summarizer whose job seam is controlled: a canned answer, or the given failure. */
    private static class SeamSummarizer extends LLMSummarizer {
        private final String canned;
        private final Exception failure;
        String hint;
        SummarySize size;

        SeamSummarizer(String canned, Exception failure) {
            super(ROOT);
            this.canned = canned;
            this.failure = failure;
        }

        @Override
        String summarizeViaJob(String text, String hint, SummarySize size) throws InterruptedException, ExecutionException {
            this.hint = hint;
            this.size = size;
            if (failure instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            if (failure != null) {
                throw new ExecutionException(failure);
            }
            return canned;
        }
    }

    @Test
    void theJobsSummaryIsWrappedWithTheOriginalLength() throws Exception {
        SeamSummarizer summarizer = new SeamSummarizer("a crisp llm summary", null);
        assertEquals("[SUMMARY: 1500 chars] a crisp llm summary", summarizer.summarize("word ".repeat(300), annotation()),
                "the prefix carries the ORIGINAL length - the signal that substantial content sits behind it");
        assertEquals("web page content", summarizer.hint, "the annotation's value describes the content to the job");
        assertEquals(SummarySize.BRIEF, summarizer.size, "and its size is the length asked for");
    }

    @Test
    void theSummarizationJobIsAChildOfTheJobTheSummarizerWasMadeFor() throws Exception {
        Identifiable thinker = Job.workflow("the-thinker", "the-thinker");
        SummarizationTool job = new LLMSummarizer(thinker).job("word ".repeat(300), annotation().value(), annotation().size());
        assertEquals(thinker.getJobId(), job.getParentJobId(), "so its cost and its record sit under the thinker that sends the conversation");
        assertEquals("web page content", job.getInput().getContext(), "the field's hint tells the job what the text is");
        assertEquals(SummarySize.BRIEF, job.getInput().getSize());
    }

    @Test
    void aFailedJobIsThrownWithItsCause_noFallbackIsMadeHere() throws Exception {
        IllegalStateException refused = new IllegalStateException("summarization job failed");
        LLMSummarizer.SummarizationFailedException thrown = assertThrows(LLMSummarizer.SummarizationFailedException.class,
                () -> new SeamSummarizer(null, refused).summarize("word ".repeat(300), annotation()));
        assertSame(refused, thrown.getCause().getCause(), "what the job reported is kept as the cause");
    }

    @Test
    void anInterruptedWaitIsThrownToo() {
        InterruptedException interrupted = new InterruptedException("cancelled");
        LLMSummarizer.SummarizationFailedException thrown = assertThrows(LLMSummarizer.SummarizationFailedException.class,
                () -> new SeamSummarizer(null, interrupted).summarize("word ".repeat(300), annotation()));
        assertSame(interrupted, thrown.getCause());
    }

    @Test
    void theSerializerCutsWhenTheJobFails_andKeepsNothing() throws Exception {
        Page page = new Page();
        page.setPlain("word ".repeat(300));
        NucleoJsonSerializer.makeSummaries(page, new SeamSummarizer(null, new IllegalStateException("summarization job failed")));
        assertNull(page.getSummaries().get("plain"), "a failed job keeps nothing");
        String shown = NucleoJsonSerializer.readTree(NucleoJsonSerializer.writeSummarized(page)).get("plain").asText();
        assertTrue(shown.startsWith("[SUMMARY: 1500 chars] word word"), "the text is cut for the write: " + shown.substring(0, 40));
        NucleoJsonSerializer.makeSummaries(page, new SeamSummarizer("a crisp llm summary", null));
        assertEquals("[SUMMARY: 1500 chars] a crisp llm summary", page.getSummaries().get("plain"), "the next making asks the job again");
    }

    @Test
    void textPastTheCeilingNeverReachesTheJob_itIsCutAndTheCutIsKept() throws Exception {
        Page page = new Page();
        page.setPlain("x".repeat(NucleoJsonSerializer.MODEL_SUMMARY_MAX_CHARS + 1));
        LLMSummarizer trap = new SeamSummarizer(null, null) {
            @Override
            String summarizeViaJob(String text, String hint, SummarySize size) {
                return fail("content past the ceiling must not be sent to a model");
            }
        };
        NucleoJsonSerializer.makeSummaries(page, trap);
        assertTrue(page.getSummaries().get("plain").startsWith("[SUMMARY: 200001 chars] xxx"), "the cut is the summary of a text this long, and is kept");
    }
}
