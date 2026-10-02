/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

import ai.redouble.nucleo.harness.artifacts.*;
import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.*;
import ch.qos.logback.core.read.*;
import org.junit.jupiter.api.*;
import org.slf4j.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The three write modes of {@link NucleoJsonSerializer} on a plain object, as the package
 * documentation states them: {@code write} reproduces every field; the summarized modes hand a
 * {@code @LLMSummarizable} String past its threshold to the summarizer (the shipped default
 * truncates, labelled with the original length) and leave every other field alone, a non-String
 * field included unless it is {@code preSummarized}, in which case an artifact's cached summary is
 * written at the leaf its JSON pointer names; the annotation itself is handed to the summarizer,
 * which is where {@code llmSafe} is honoured; a field without a hint is warned about once;
 * {@code writeSummarizedWithRefs} additionally replaces an artifact with its reference and
 * registers it, and an artifact with no reference and no registry is a framework fault. A
 * serialization failure is reported by describing the object's fields by size, never by
 * reproducing them.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class NucleoJsonSerializerWriteModesTest {

    public static class Page {
        @LLMSummarizable(value = "page text", threshold = 20, size = SummarySize.BRIEF)
        private String body;
        @LLMSummarizable(value = "not a string", threshold = 1)
        private List<String> lines;
        private String title;

        public String getBody() { return body; }
        public void setBody(String body) { this.body = body; }
        public List<String> getLines() { return lines; }
        public void setLines(List<String> lines) { this.lines = lines; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
    }

    public static class Labelled {
        @LLMSummarizable(value = "a sequence", threshold = 5, staticSummary = "an amino acid sequence")
        private String sequence;

        public String getSequence() { return sequence; }
        public void setSequence(String sequence) { this.sequence = sequence; }
    }

    @TypeAlias("test:modes")
    public static class Held extends AbstractArtifact {
        private String value;

        public Held() {}
        public Held(String value) { this.value = value; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }

    public static class Holder {
        public Held held;
        public String label;
    }

    public static class Fragile {
        @LLMSummarizable(value = "a formatted table", threshold = 5, llmSafe = false)
        private String table = "| a | b |\n| 1 | 2 |";

        public String getTable() { return table; }
        public void setTable(String table) { this.table = table; }
    }

    public static class Hintless {
        @LLMSummarizable(threshold = 5)
        private String body = "a body with no hint declared";

        public String getBody() { return body; }
        public void setBody(String body) { this.body = body; }
    }

    @TypeAlias("test:bundle")
    public static class Bundle extends AbstractArtifact {
        @LLMSummarizable(value = "a served result", preSummarized = true)
        private Map<String, Object> data;

        public Map<String, Object> getData() { return data; }
        public void setData(Map<String, Object> data) { this.data = data; }
    }

    /** A bean Jackson cannot serialize: the cycle makes the default serializer fail. */
    public static class Loop {
        public Loop self = this;
        public List<String> many = List.of("a", "b", "c", "d", "e");
        public String text = "x".repeat(500);
    }

    private static Page page() {
        Page page = new Page();
        page.setBody("a body of text that is well past the twenty character threshold, and then some more words so that a BRIEF budget of two hundred characters is exceeded and the truncation shows; the tail of this sentence never reaches the summarized output, and its last word is OMEGA");
        page.setLines(List.of("one", "two"));
        page.setTitle("short");
        return page;
    }

    @Test
    void writeReproducesEveryFieldWhateverTheAnnotationsSay() {
        String json = NucleoJsonSerializer.write(page());
        assertTrue(json.contains("well past the twenty"), "the full body");
        assertFalse(json.contains("[SUMMARY"), "no summarization in the plain write");
    }

    @Test
    void summarizedModesTruncateALongStringByDefaultAndLeaveTheRestAlone() {
        String label = "[SUMMARY: " + page().getBody().length() + " chars]";
        String json = NucleoJsonSerializer.writeSummarized(page());
        assertTrue(json.contains(label), "the default summarizer labels the summary with the original length: " + json);
        assertFalse(json.contains("OMEGA"), "and truncates to the size's budget, so the tail of the body is gone: " + json);
        assertTrue(json.contains("well past the twenty"), "while the head of the body is kept");
        assertTrue(json.contains("\"short\""), "a field below its threshold is written in full");
        assertTrue(json.contains("\"one\"") && json.contains("\"two\""), "a non-String summarizable field is not summarized");
        String compact = NucleoJsonSerializer.writeSummarizedCompact(page());
        assertFalse(compact.contains("\n"), "the compact form carries no formatting whitespace");
        assertTrue(compact.contains(label), "and summarizes the same way");
    }

    @Test
    void aStaticSummaryStandsInForTheText() {
        Labelled labelled = new Labelled();
        labelled.setSequence("MKTAYIAKQRQISFVKSHFSRQ");
        String json = NucleoJsonSerializer.writeSummarized(labelled);
        assertTrue(json.contains("[SUMMARY: 22 chars] an amino acid sequence"), "the declared label replaces content a paraphrase would corrupt: " + json);
        assertFalse(json.contains("MKTAYIAK"), "the sequence itself is not reproduced");
    }

    @Test
    void theProvidedSummarizerIsTheOneCalled() {
        Summarizer marker = (text, annotation) -> "[" + annotation.value() + ": " + text.length() + "]";
        String json = NucleoJsonSerializer.writeSummarized(page(), marker);
        assertTrue(json.contains("[page text: " + page().getBody().length() + "]"), "the caller's summarizer, with the field's hint: " + json);
    }

    @Test
    void theAnnotationReachesTheSummarizerWhichIsWhereLlmSafeIsHonoured() {
        List<Boolean> seen = new ArrayList<>();
        Summarizer recorder = (text, annotation) -> {
            seen.add(annotation.llmSafe());
            return "[recorded]";
        };
        NucleoJsonSerializer.writeSummarized(new Fragile(), recorder);
        assertEquals(List.of(false), seen, "the serializer hands the annotation over as declared; what llmSafe=false changes is the summarizer's decision");
    }

    @Test
    void aFieldWithoutAHintIsWarnedAboutOncePerProcess() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(NucleoJsonSerializer.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        try {
            NucleoJsonSerializer.writeSummarized(new Hintless());
            NucleoJsonSerializer.writeSummarized(new Hintless());
            long warnings = captured.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN && event.getFormattedMessage().contains("Hintless.body"))
                    .count();
            assertEquals(1, warnings, "one warning names the field, and the second write repeats nothing");
        }
        finally {
            logger.detachAppender(captured);
        }
    }

    @Test
    void aPreSummarizedFieldWritesTheCachedSummaryAtItsPointerAndTheRestAsItIs() {
        Bundle bundle = new Bundle();
        Map<String, Object> deep = new LinkedHashMap<>();
        deep.put("text", "the full long text of the served page");
        deep.put("n", 1);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deep", deep);
        data.put("list", List.of("a"));
        bundle.setData(data);
        bundle.cacheSummary("/deep/text", new SummarizedField("the full long text of the served page", "[cached]"));
        String json = NucleoJsonSerializer.writeSummarizedCompact(bundle);
        assertTrue(json.contains("\"text\":\"[cached]\""), "the leaf with a cached summary is written as the summary: " + json);
        assertFalse(json.contains("full long text"), "and its value is not reproduced");
        assertTrue(json.contains("\"n\":1") && json.contains("\"list\":[\"a\"]"), "leaves without one are written as they are");
        assertTrue(NucleoJsonSerializer.writeCompact(bundle).contains("full long text"), "the plain write reproduces the value");
    }

    @Test
    void anArtifactWithNoRefAndNoRegistryIsAFrameworkFault() {
        RuntimeException failure = assertThrows(RuntimeException.class, () -> NucleoJsonSerializer.writeSummarizedWithRefs(new Held("x"), null));
        Throwable cause = failure;
        while (cause != null && !(cause instanceof IllegalStateException)) {
            cause = cause.getCause();
        }
        assertNotNull(cause, "the serialization failure carries the fault as its cause: " + failure.getMessage());
        assertTrue(cause.getMessage().contains("has no ref assigned"), cause.getMessage());
    }

    @Test
    void withRefsReplacesAnArtifactWithItsReferenceAndRegistersIt() {
        ArtifactRegistry registry = new ArtifactRegistry();
        Holder holder = new Holder();
        holder.held = new Held("secret body");
        holder.label = "outer";
        String json = NucleoJsonSerializer.writeSummarizedWithRefs(holder, registry);
        assertTrue(json.contains("\"@ref\""), "the artifact becomes a reference: " + json);
        assertFalse(json.contains("secret body"), "its body stays out of the prompt");
        assertSame(holder.held, registry.get(holder.held.getArtifactRef()), "and it is registered under that reference");
        assertTrue(NucleoJsonSerializer.writeSummarized(holder).contains("secret body"), "the summarized mode without refs writes the artifact in full");
    }

    @Test
    void aSerializationFailureDescribesFieldsBySizeWithoutReproducingThem() {
        RuntimeException failure = assertThrows(RuntimeException.class, () -> NucleoJsonSerializer.write(new Loop()));
        assertTrue(failure.getMessage().contains("Failed to serialize " + Loop.class.getName()), failure.getMessage());
        assertTrue(failure.getMessage().contains("[5 element(s)]"), "a collection is reported by its size: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("[500 chars]"), "a string is reported by its length: " + failure.getMessage());
        assertFalse(failure.getMessage().contains("x".repeat(300)), "and never reproduced past a bounded prefix");
    }
}
