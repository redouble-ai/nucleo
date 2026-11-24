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
 * it is handed the field's value, whatever the value's type, when the value is longer than
 * the threshold; it is used in place of the summarizer of the serialization; on an artifact
 * it runs once for a value; a field that names none is written as it always was; and a
 * summarizer that throws, returns nothing or cannot be made never fails the serialization.
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

    @TypeAlias("test:fieldsummarizer")
    public static class Row extends AbstractArtifact {
        @LLMSummarizable(value = "a payload", threshold = 20, summarizer = KeyCount.class)
        private JsonNode payload;
        @LLMSummarizable(value = "a trace", threshold = 20, summarizer = LengthOnly.class)
        private String trace;
        @LLMSummarizable(value = "a note", threshold = 20)
        private String note;
        @LLMSummarizable(value = "a tree with no summarizer", threshold = 20)
        private JsonNode plain;

        public JsonNode getPayload() { return payload; }
        public void setPayload(JsonNode payload) { this.payload = payload; }
        public String getTrace() { return trace; }
        public void setTrace(String trace) { this.trace = trace; }
        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }
        public JsonNode getPlain() { return plain; }
        public void setPlain(JsonNode plain) { this.plain = plain; }
    }

    public static class Broken {
        @LLMSummarizable(value = "text", threshold = 20, summarizer = NoPlainConstructor.class)
        private String text = "some text";

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
    }

    private static ObjectNode tree(int keys) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        for (int i = 0; i < keys; i++) {
            node.put("key" + i, "value" + i);
        }
        return node;
    }

    private static JsonNode written(Row row) throws Exception {
        return NucleoJsonSerializer.readTree(NucleoJsonSerializer.writeSummarized(row, (text, annotation) -> "[CALLER]"));
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
    public void aLongTextIsWrittenByItsFieldsSummarizerAndNeverByTheCallers() throws Exception {
        Row row = new Row();
        row.setTrace("x".repeat(40));
        row.setNote("y".repeat(40));
        JsonNode written = written(row);
        assertEquals("[40 characters]", written.get("trace").asText(), "the field names its summarizer, so the caller's is not used for it");
        assertEquals("[CALLER]", written.get("note").asText(), "a field that names none is summarized by the caller's summarizer, as before");
        Row brief = new Row();
        brief.setTrace("short");
        assertEquals("short", written(brief).get("trace").asText(), "a text under the threshold is written whole");
    }

    @Test
    public void aTreeThatNamesNoSummarizerIsWrittenWhole() throws Exception {
        Row row = new Row();
        row.setPlain(tree(5));
        assertEquals(tree(5), written(row).get("plain"), "a field of another type than text that names no summarizer is written whole, as before");
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

    public static class Failing {
        @LLMSummarizable(value = "a tree", threshold = 20, summarizer = Throwing.class)
        private JsonNode tree = tree(5);
        @LLMSummarizable(value = "a text", threshold = 20, summarizer = Silent.class)
        private String text = "word ".repeat(400);

        public JsonNode getTree() { return tree; }
        public void setTree(JsonNode tree) { this.tree = tree; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
    }

    @Test
    public void aSummarizerThatFailsNeverFailsTheSerialization() throws Exception {
        Failing failing = new Failing();
        JsonNode written = NucleoJsonSerializer.readTree(NucleoJsonSerializer.writeSummarized(failing));
        String treeAsText = NucleoJsonSerializer.writeCompact(tree(5));
        assertEquals(new TruncatingSummarizer().summarize(treeAsText, Failing.class.getDeclaredField("tree").getAnnotation(LLMSummarizable.class)),
                     written.get("tree").asText(), "a tree whose summarizer throws is cut short as its JSON text");
        assertEquals(new TruncatingSummarizer().summarize(failing.getText(), Failing.class.getDeclaredField("text").getAnnotation(LLMSummarizable.class)),
                     written.get("text").asText(), "a summarizer that returns nothing is passed over the same way");
    }

    @Test
    public void aSummarizerThatCannotBeMadeLeavesItsFieldCutAsPlainText() throws Exception {
        Broken broken = new Broken();
        broken.setText("word ".repeat(400));
        JsonNode written = NucleoJsonSerializer.readTree(NucleoJsonSerializer.writeSummarized(broken));
        assertEquals(new TruncatingSummarizer().summarize(broken.getText(), Broken.class.getDeclaredField("text").getAnnotation(LLMSummarizable.class)),
                     written.get("text").asText(), "a class with no public no-argument constructor is logged and passed over");
    }
}
