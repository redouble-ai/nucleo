/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

import ai.redouble.nucleo.harness.artifacts.*;
import org.junit.jupiter.api.*;

import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that {@code @LLMSummarizable} field summaries are cached on artifacts
 * so repeated serializations of the same artifact re-use the computed summary
 * rather than calling the {@link Summarizer} again.
 *
 * <p>Non-artifacts are transient by design and receive no caching - their
 * summarizer is called every time.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-14)
 */
public class NucleoJsonSerializerSummarizationTest {

    @TypeAlias("test:longtext")
    public static class LongTextArtifact extends AbstractArtifact {
        @LLMSummarizable(value = "test text", threshold = 10)
        private String body;

        public LongTextArtifact() {}
        public LongTextArtifact(String body) { this.body = body; }
        public String getBody() { return body; }
        public void setBody(String body) { this.body = body; }
    }

    public static class TransientWithSummarizable {
        @LLMSummarizable(value = "test text", threshold = 10)
        private String body;

        public TransientWithSummarizable() {}
        public TransientWithSummarizable(String body) { this.body = body; }
        public String getBody() { return body; }
        public void setBody(String body) { this.body = body; }
    }

    /**
     * Counting summarizer that records how many times it was invoked so tests
     * can verify caching behavior.
     */
    private static class CountingSummarizer implements Summarizer {
        final AtomicInteger calls = new AtomicInteger(0);
        @Override
        public String summarize(String text, LLMSummarizable annotation) {
            calls.incrementAndGet();
            return "[SUMMARY]";
        }
    }

    @Test
    public void artifactSummarizerCalledOnceAcrossRepeatedSerializations() {
        ArtifactRegistry registry = new ArtifactRegistry();
        CountingSummarizer summarizer = new CountingSummarizer();
        LongTextArtifact a = new LongTextArtifact("a very long body of text that exceeds the threshold");

        // Serialize body-level (not top-level, to exercise @LLMSummarizable path)
        // by wrapping in a plain POJO container and summarizing that.
        // Actually, since top-level artifact becomes @ref and skips the body,
        // we need to force body-level summarization. Use writeSummarized (no refs)
        // which serializes the artifact body and summarizes its fields.
        String first = NucleoJsonSerializer.writeSummarized(a, summarizer);
        String second = NucleoJsonSerializer.writeSummarized(a, summarizer);
        String third = NucleoJsonSerializer.writeSummarized(a, summarizer);

        assertEquals(1, summarizer.calls.get(),
            "summarizer should be called exactly once; cached on artifact for subsequent passes");
        assertTrue(first.contains("[SUMMARY]"), "summary placeholder should appear in output: " + first);
        assertEquals(first, second);
        assertEquals(first, third);
    }

    @Test
    public void transientPojoSummarizerCalledEveryTime() {
        CountingSummarizer summarizer = new CountingSummarizer();
        TransientWithSummarizable t = new TransientWithSummarizable("a very long body of text that exceeds the threshold");

        NucleoJsonSerializer.writeSummarized(t, summarizer);
        NucleoJsonSerializer.writeSummarized(t, summarizer);
        NucleoJsonSerializer.writeSummarized(t, summarizer);

        assertEquals(3, summarizer.calls.get(),
            "non-artifact should be summarized every time; no caching mechanism");
    }

    @Test
    public void shortTextBelowThresholdIsNotSummarized() {
        CountingSummarizer summarizer = new CountingSummarizer();
        LongTextArtifact a = new LongTextArtifact("short");

        String json = NucleoJsonSerializer.writeSummarized(a, summarizer);

        assertEquals(0, summarizer.calls.get(), "text below threshold should not call summarizer");
        assertTrue(json.contains("\"short\""), "full text should be preserved: " + json);
    }
}
