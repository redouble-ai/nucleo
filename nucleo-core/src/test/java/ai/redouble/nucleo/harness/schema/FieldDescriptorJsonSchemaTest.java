/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.models.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The standard JSON Schema rendering of a POJO definition: enum constraints and examples
 * are data a consumer outside the process can act on, not prose.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-02)
 */
public class FieldDescriptorJsonSchemaTest {

    public enum Mode {
        FAST, CAREFUL
    }

    public static class Constrained {
        @LLMRequired
        @LLMDescription("How to run")
        private Mode mode;
        @LLMDescription("Fallback modes in order")
        private List<Mode> fallbacks;
        @LLMDescription("Ordinary text")
        @LLMExample({"alpha", "beta"})
        private String label;
        @LLMDescription("A count")
        @LLMExample({"3", "5"})
        private int count;
        @LLMDescription("Depth of the run")
        private Depth depth;

        public Mode getMode() {
            return mode;
        }

        public void setMode(Mode mode) {
            this.mode = mode;
        }

        public List<Mode> getFallbacks() {
            return fallbacks;
        }

        public void setFallbacks(List<Mode> fallbacks) {
            this.fallbacks = fallbacks;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }

        public Depth getDepth() {
            return depth;
        }

        public void setDepth(Depth depth) {
            this.depth = depth;
        }
    }

    public static class Point {
        @LLMDescription("Where")
        private int x;

        public int getX() {
            return x;
        }

        public void setX(int x) {
            this.x = x;
        }
    }

    public static class Keyed {
        @LLMDescription("Count per key")
        private Map<String, Long> counts;
        @LLMDescription("Whatever per key")
        private Map<String, Object> anything;
        @LLMDescription("A point per key")
        private Map<String, Point> points;
        @LLMDescription("A mode per key")
        private Map<String, Mode> modes;
        @LLMDescription("A value of no fixed shape")
        private Object payload;

        public Map<String, Long> getCounts() {
            return counts;
        }

        public void setCounts(Map<String, Long> counts) {
            this.counts = counts;
        }

        public Map<String, Object> getAnything() {
            return anything;
        }

        public void setAnything(Map<String, Object> anything) {
            this.anything = anything;
        }

        public Map<String, Point> getPoints() {
            return points;
        }

        public void setPoints(Map<String, Point> points) {
            this.points = points;
        }

        public Map<String, Mode> getModes() {
            return modes;
        }

        public void setModes(Map<String, Mode> modes) {
            this.modes = modes;
        }

        public Object getPayload() {
            return payload;
        }

        public void setPayload(Object payload) {
            this.payload = payload;
        }
    }

    private static JsonNode schema() throws IOException {
        return NucleoJsonSerializer.readTree(PojoResponseHandler.generateSchema(Constrained.class).toJsonSchema());
    }

    /** A map is an object with no declared keys and one stated value shape; Object is a value of no stated shape. */
    @Test
    void mapsPublishTheirValueShapeAndObjectPublishesNone() throws IOException {
        JsonNode keyed = NucleoJsonSerializer.readTree(PojoResponseHandler.generateSchema(Keyed.class).toJsonSchema()).path("properties");
        JsonNode counts = keyed.path("counts");
        assertEquals("object", counts.path("type").asText());
        assertEquals("integer", counts.path("additionalProperties").path("type").asText(), counts.toString());
        assertEquals("Count per key", counts.path("description").asText());
        assertTrue(keyed.path("anything").path("additionalProperties").isBoolean() && keyed.path("anything").path("additionalProperties").asBoolean(),
                "a map to Object admits any value: " + keyed.path("anything"));
        JsonNode points = keyed.path("points").path("additionalProperties");
        assertEquals("object", points.path("type").asText());
        assertEquals("integer", points.path("properties").path("x").path("type").asText(), "the value's own definition: " + points);
        assertEquals(List.of("FAST", "CAREFUL"), texts(keyed.path("modes").path("additionalProperties").path("enum")));
        assertFalse(keyed.path("payload").has("type"), "Object publishes no type: " + keyed.path("payload"));
        assertEquals("A value of no fixed shape", keyed.path("payload").path("description").asText());
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(n -> values.add(n.asText()));
        return values;
    }

    @Test
    void enumFieldPublishesItsConstants() throws IOException {
        JsonNode mode = schema().path("properties").path("mode");
        assertEquals("string", mode.path("type").asText());
        assertEquals(List.of("FAST", "CAREFUL"), texts(mode.path("enum")));
        assertEquals("How to run", mode.path("description").asText());
        assertTrue(texts(schema().path("required")).contains("mode"));
    }

    @Test
    void enumConstantsAreTheSerializerWireForm() throws IOException {
        List<String> published = texts(schema().path("properties").path("depth").path("enum"));
        for (Depth depth : Depth.values()) {
            assertTrue(published.contains(NucleoJsonSerializer.valueToTree(depth).asText()), published.toString());
        }
        assertEquals(Depth.values().length, published.size());
    }

    @Test
    void collectionOfEnumsConstrainsItsItems() throws IOException {
        JsonNode fallbacks = schema().path("properties").path("fallbacks");
        assertEquals("array", fallbacks.path("type").asText());
        assertEquals("string", fallbacks.path("items").path("type").asText());
        assertEquals(List.of("FAST", "CAREFUL"), texts(fallbacks.path("items").path("enum")));
    }

    @Test
    void examplesPublishAsTheStandardKeyword() throws IOException {
        JsonNode label = schema().path("properties").path("label");
        assertEquals(List.of("alpha", "beta"), texts(label.path("examples")));
        assertEquals("Ordinary text", label.path("description").asText(), "examples leave the description");
        JsonNode count = schema().path("properties").path("count");
        assertTrue(count.path("examples").get(0).isInt(), "numeric examples publish as numbers");
        assertEquals(3, count.path("examples").get(0).asInt());
    }

    @Test
    void llmNotationNamesEnumsAndTheirValues() throws IOException {
        JsonNode notation = NucleoJsonSerializer.readTree(PojoResponseHandler.generateSchema(Constrained.class).toLLMSchema());
        JsonNode mode = notation.path("@fields").path("mode");
        assertEquals("enum", mode.path("@type").asText());
        assertEquals(List.of("FAST", "CAREFUL"), texts(mode.path("@values")));
        JsonNode fallbacks = notation.path("@fields").path("fallbacks");
        assertEquals("array of enum", fallbacks.path("@type").asText());
        assertEquals(List.of("FAST", "CAREFUL"), texts(fallbacks.path("@values")));
        assertTrue(notation.path("@fields").path("label").has("@examples"));
        assertFalse(notation.toString().contains("\"enum\":"), "the notation carries no JSON Schema keywords");
    }
}
