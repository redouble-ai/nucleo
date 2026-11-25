/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

import ai.redouble.nucleo.harness.artifacts.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;

import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the summarizer a field names for itself with {@link LLMSummarizable#summarizer()}:
 * it is handed the field's value, whatever the value's type, when the JSON of the value is
 * longer than the threshold; it is used in place of a model, with or without one at hand;
 * it runs once for a value and its answer is kept on the artifact; and a summarizer that
 * throws, returns nothing or cannot be made never fails the serialization: the value is
 * cut for that write and nothing is kept.
 *
 * @author Andrey Santrosyan
 * @since 0.1.6 (2026-11-24)
 */
public class FieldSummarizerTest {
    static final AtomicInteger TREE_CALLS = new AtomicInteger();

    /** Says how many keys a JSON object has. */
    public static class KeyCount implements FieldSummarizer<JsonNode> {
        @Override
        public String summarize(JsonNode value, LLMSummarizable annotation) {
            TREE_CALLS.incrementAndGet();
            return "[" + value.size() + " keys of " + annotation.value() + "]";
        }
    }

    /** Says how long a text is. */
    public static class LengthOnly implements FieldSummarizer<String> {
        @Override
        public String summarize(String value, LLMSummarizable annotation) {
            return "[" + value.length() + " characters]";
        }
    }

    /** A summarizer that cannot be made: its only constructor takes an argument. */
    public static class NoPlainConstructor implements FieldSummarizer<String> {
        public NoPlainConstructor(String unused) {}

        @Override
        public String summarize(String value, LLMSummarizable annotation) {
            return value;
        }
    }

    /** A summarizer that always fails. */
    public static class Throwing implements FieldSummarizer<JsonNode> {
        @Override
        public String summarize(JsonNode value, LLMSummarizable annotation) {
            throw new IllegalStateException("this summarizer cannot summarize");
        }
    }

    /** A summarizer that returns nothing. */
    public static class Silent implements FieldSummarizer<String> {
        @Override
        public String summarize(String value, LLMSummarizable annotation) {
            return null;
        }
    }

    @TypeAlias("test:fieldsummarizer")
    public static class Row extends AbstractArtifact {
        @LLMSummarizable(value = "a payload", threshold = 20, summarizer = KeyCount.class)
        private JsonNode payload;
        @LLMSummarizable(value = "a trace", threshold = 20, summarizer = LengthOnly.class)
        private String trace;
        @LLMSummarizable(value = "a note", threshold = 20)
        private String note;

        public JsonNode getPayload() { return payload; }
        public void setPayload(JsonNode payload) { this.payload = payload; }
        public String getTrace() { return trace; }
        public void setTrace(String trace) { this.trace = trace; }
        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }
    }

    @TypeAlias("test:fieldsummarizer:failing")
    public static class Failing extends AbstractArtifact {
        @LLMSummarizable(value = "a tree", threshold = 20, summarizer = Throwing.class)
        private JsonNode tree = tree(5);
        @LLMSummarizable(value = "a text", threshold = 20, summarizer = Silent.class)
        private String text = "word ".repeat(400);
        @LLMSummarizable(value = "another text", threshold = 20, summarizer = NoPlainConstructor.class)
        private String unmade = "word ".repeat(400);

        public JsonNode getTree() { return tree; }
        public void setTree(JsonNode tree) { this.tree = tree; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public String getUnmade() { return unmade; }
        public void setUnmade(String unmade) { this.unmade = unmade; }
    }

    private static ObjectNode tree(int keys) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        for (int i = 0; i < keys; i++) {
            node.put("key" + i, "value" + i);
        }
        return node;
    }

    private static JsonNode written(Object artifact) throws Exception {
        return NucleoJsonSerializer.readTree(NucleoJsonSerializer.writeSummarized(artifact));
    }

    private static String cut(String text, Class<?> type, String field) throws Exception {
        return new TruncatingSummarizer().summarize(text, type.getDeclaredField(field).getAnnotation(LLMSummarizable.class));
    }

    @Test
    public void aLongTreeIsWrittenByTheSummarizerItsFieldNames() throws Exception {
        Row row = new Row();
        row.setPayload(tree(5));
        assertEquals("[5 keys of a payload]", written(row).get("payload").asText(), "the field's summarizer is handed the tree itself");
    }

    @Test
    public void aTreeNoLongerThanTheThresholdIsWrittenWhole() throws Exception {
        Row row = new Row();
        row.setPayload(tree(1));
        assertEquals(tree(1), written(row).get("payload"), "the JSON of the value is 17 characters, under the threshold of 20");
    }

    @Test
    public void aFieldThatNamesItsSummarizerNeverReachesAModel() throws Exception {
        Row row = new Row();
        row.setTrace("x".repeat(40));
        row.setNote("y".repeat(40));
        assertEquals("[40 characters]", written(row).get("trace").asText(), "the named class needs no model, so a write before any model already shows its summary");
        NucleoJsonSerializer.makeSummaries(row, (text, annotation) -> "[MODEL]");
        JsonNode written = written(row);
        assertEquals("[40 characters]", written.get("trace").asText(), "the field names its summarizer, so the model is not asked for it");
        assertEquals("[MODEL]", written.get("note").asText(), "a field that names none is summarized by the model");
        Row brief = new Row();
        brief.setTrace("short");
        assertEquals("short", written(brief).get("trace").asText(), "a text under the threshold is written whole");
    }

    @Test
    public void theSummarizerRunsOnceForAnArtifactAndNeverForAPlainWrite() throws Exception {
        Row row = new Row();
        row.setPayload(tree(7));
        TREE_CALLS.set(0);
        assertEquals(tree(7), NucleoJsonSerializer.readTree(NucleoJsonSerializer.write(row)).get("payload"), "write shows the value whole");
        assertEquals(0, TREE_CALLS.get(), "write summarizes nothing");
        written(row);
        written(row);
        assertEquals(1, TREE_CALLS.get(), "the summary is kept on the artifact after the first serialization");
        assertEquals("[7 keys of a payload]", row.getSummaries().get("payload"));
    }

    @Test
    public void aSummarizerThatFailsNeverFailsTheSerializationAndKeepsNothing() throws Exception {
        Failing failing = new Failing();
        JsonNode written = written(failing);
        assertEquals(cut(NucleoJsonSerializer.writeCompact(tree(5)), Failing.class, "tree"), written.get("tree").asText(),
                     "a tree whose summarizer throws is cut short as its JSON text");
        assertEquals(cut(failing.getText(), Failing.class, "text"), written.get("text").asText(),
                     "a summarizer that returns nothing is passed over the same way");
        assertEquals(cut(failing.getUnmade(), Failing.class, "unmade"), written.get("unmade").asText(),
                     "and so is a class with no public no-argument constructor");
        assertTrue(failing.getSummaries().all().isEmpty(), "a cut that stands in for a failed summarizer is not kept");
        NucleoJsonSerializer.makeSummaries(failing, (text, annotation) -> fail("a field that names its summarizer is never handed to a model, even when the class fails"));
    }
}
