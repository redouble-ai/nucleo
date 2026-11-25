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
 * The write modes of {@link NucleoJsonSerializer}, as the package documentation states
 * them: {@code write} reproduces every field; the summarized modes show an artifact's
 * {@code @LLMSummarizable} field past its threshold as a summary (a cut, labelled with the
 * original length, until a model's summary is made) and leave every other field alone; a
 * fixed phrase stands in for the text; content marked {@code llmSafe = false} is cut and
 * never reaches a model; a {@code preSummarized} tree is summarized leaf by leaf, each
 * summary kept under the field's name and the leaf's JSON pointer; a field without a hint
 * is warned about once; {@code writeSummarizedWithRefs} additionally replaces an artifact
 * with its reference and registers it, and an artifact with no reference and no registry
 * is a framework fault; the model-facing fields of one artifact are its serialized form
 * without what {@code @LLMContextIgnore} hides, whole and with held artifacts as
 * references; the stored form of a prompt object keeps every field. A serialization
 * failure is reported by describing the object's fields by size, never by reproducing them.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class NucleoJsonSerializerWriteModesTest {

    @TypeAlias("test:modes:page")
    public static class Page extends AbstractArtifact {
        @LLMSummarizable(value = "page text", threshold = 20, size = SummarySize.BRIEF)
        private String body;
        @LLMSummarizable(value = "not a string", threshold = 1)
        private List<String> lines;
        private String title;
        @LLMContextIgnore
        private String internalNote = "kept from the model";
        private Held attachment;

        public String getInternalNote() { return internalNote; }
        public void setInternalNote(String internalNote) { this.internalNote = internalNote; }
        public Held getAttachment() { return attachment; }
        public void setAttachment(Held attachment) { this.attachment = attachment; }
        public String getBody() { return body; }
        public void setBody(String body) { this.body = body; }
        public List<String> getLines() { return lines; }
        public void setLines(List<String> lines) { this.lines = lines; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
    }

    @TypeAlias("test:modes:labelled")
    public static class Labelled extends AbstractArtifact {
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

    @TypeAlias("test:modes:fragile")
    public static class Fragile extends AbstractArtifact {
        @LLMSummarizable(value = "a formatted table", threshold = 5, llmSafe = false)
        private String table = "| a | b |\n| 1 | 2 |";

        public String getTable() { return table; }
        public void setTable(String table) { this.table = table; }
    }

    @TypeAlias("test:modes:hintless")
    public static class Hintless extends AbstractArtifact {
        @LLMSummarizable(threshold = 5)
        private String body = "a body with no hint declared";

        public String getBody() { return body; }
        public void setBody(String body) { this.body = body; }
    }

    @TypeAlias("test:bundle")
    public static class Bundle extends AbstractArtifact {
        @LLMSummarizable(value = "a served result", preSummarized = true, threshold = 20, llmSafe = false)
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
        assertTrue(json.contains(label), "with no model's summary made yet, the cut labels itself with the original length: " + json);
        assertFalse(json.contains("OMEGA"), "and keeps the size's budget, so the tail of the body is gone: " + json);
        assertTrue(json.contains("well past the twenty"), "while the head of the body is kept");
        assertTrue(json.contains("\"short\""), "a field that is not annotated is written in full");
        assertTrue(json.contains("[SUMMARY: 13 chars] [\\\"one\\\",\\\"two\\\"]"), "a list past its threshold is one summary of its JSON: " + json);
        assertFalse(json.contains("kept from the model"), "a field marked @LLMContextIgnore is not shown: " + json);
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
    void aModelIsHandedTheTextAndTheAnnotationOfAFieldThatNeedsOne() {
        Page page = page();
        NucleoJsonSerializer.makeSummaries(page, (text, annotation) -> "[" + annotation.value() + ": " + text.length() + "]");
        String json = NucleoJsonSerializer.writeSummarized(page);
        assertTrue(json.contains("[page text: " + page.getBody().length() + "]"), "the model's summary, made with the field's hint: " + json);
    }

    @Test
    void contentThatMustNotBeParaphrasedIsCutAndNeverReachesAModel() {
        Fragile fragile = new Fragile();
        NucleoJsonSerializer.makeSummaries(fragile, (text, annotation) -> fail("llmSafe = false content must not be sent to a model"));
        assertTrue(NucleoJsonSerializer.writeSummarized(fragile).contains("[SUMMARY: 19 chars] | a | b |"), "the cut quotes the original text");
        assertNotNull(fragile.getSummaries().get("table"), "and is kept, being the field's summary for good");
    }

    @Test
    void theModelFacingFieldsOfAnArtifactAreItsSerializedFormWholeWithHeldArtifactsAsReferences() {
        Page page = page();
        page.setAttachment(new Held("held body"));
        page.getAttachment().setArtifactRef("«artifact:test:modes~h1»");
        com.fasterxml.jackson.databind.node.ObjectNode fields = NucleoJsonSerializer.modelFacingFields(page);
        assertEquals(page.getBody(), fields.get("body").asText(), "a long field is whole here: this is what a model reads a field back from");
        assertEquals(2, fields.get("lines").size(), "a list is the JSON array it is written as");
        assertFalse(fields.has("internal_note"), "a field marked @LLMContextIgnore is not readable by a model");
        assertEquals("«artifact:test:modes~h1»", fields.get("attachment").get("@ref").asText(), "an artifact it holds is its reference");
        assertEquals(Set.of("artifact_ref", "body", "lines", "title", "attachment"), NucleoJsonSerializer.modelFacingFieldNames(Page.class),
                "the names a model may ask for, in the spelling they are serialized in");
    }

    @Test
    void theStoredFormOfAnObjectKeepsEveryFieldWholeAndItsArtifactsAsReferences() {
        ArtifactRegistry registry = new ArtifactRegistry();
        Holder holder = new Holder();
        holder.held = new Held("secret body");
        holder.label = "outer";
        String stored = NucleoJsonSerializer.writeStoredWithRefs(holder, registry);
        assertTrue(stored.contains("\"@ref\"") && !stored.contains("secret body"), "the artifact is stored in the registry, and here as its reference: " + stored);
        assertSame(holder.held, registry.get(holder.held.getArtifactRef()), "the write registers it");
        Stamped stamped = new Stamped();
        assertTrue(NucleoJsonSerializer.writeStoredWithRefs(stamped, registry).contains("kept from the model"),
                "a field hidden from a model is still stored: the object is read back from this text");
        assertFalse(NucleoJsonSerializer.writeSummarizedWithRefs(stamped, registry).contains("kept from the model"));
    }

    public static class Stamped {
        @LLMContextIgnore
        public String internalNote = "kept from the model";
        public String label = "shown";
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
    void aPreSummarizedFieldSummarizesEachLongLeafUnderItsPointerAndWritesTheRestAsItIs() {
        Bundle bundle = new Bundle();
        Map<String, Object> deep = new LinkedHashMap<>();
        deep.put("text", "the full long text of the served page");
        deep.put("n", 1);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deep", deep);
        data.put("list", List.of("a"));
        bundle.setData(data);
        String json = NucleoJsonSerializer.writeSummarizedCompact(bundle);
        assertTrue(json.contains("\"text\":\"[SUMMARY: 37 chars] the full long text of the served page\""), "a text leaf past the threshold is written as its summary: " + json);
        assertEquals(Set.of("data/deep/text"), bundle.getSummaries().all().keySet(), "kept under the field's name and the leaf's JSON pointer");
        assertTrue(json.contains("\"n\":1") && json.contains("\"list\":[\"a\"]"), "every other leaf is written as it is");
        Bundle restored = new Bundle();
        restored.setData(data);
        restored.getSummaries().keep("data/deep/text", "[kept]");
        assertTrue(NucleoJsonSerializer.writeSummarizedCompact(restored).contains("\"text\":\"[kept]\""), "a summary already kept under that key is the one written");
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
        assertTrue(NucleoJsonSerializer.writeSummarized(holder).contains("secret body"), "the summarized mode without refs writes the artifact inline");
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
