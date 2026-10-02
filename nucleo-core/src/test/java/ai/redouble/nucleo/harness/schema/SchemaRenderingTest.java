/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

import ai.redouble.nucleo.harness.conversation.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the two renderings of a {@link PojoDefinition} promise beyond the field types: every
 * object node carries the type's simple name as {@code title}; a temporal field publishes its
 * {@code format}; an integer or long publishes its width as bounds; a collection of scalars
 * publishes its {@code items}; a cyclic type renders as a {@code $ref} into a {@code $defs}
 * section that only the root carries, and as the bare type name in the {@code @}-notation
 * ({@code array of <name>} for a collection), keeping its own description, examples and
 * keywords beside the reference;
 * two classes sharing one simple name are refused; the {@code (REQUIRED)} token and the
 * examples leave the description in both renderings; a description override replaces the
 * description in the JSON Schema form.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class SchemaRenderingTest {

    public static class Inner {
        @LLMDescription("A count")
        private int count;

        public int getCount() { return count; }
        public void setCount(int count) { this.count = count; }
    }

    public static class Outer {
        @LLMRequired
        @LLMDescription("When")
        private LocalDate day;
        @LLMDescription("At")
        private LocalDateTime moment;
        @LLMDescription("Wide")
        private long wide;
        @LLMDescription("Words")
        private List<String> words;
        @LLMDescription("Nested")
        private Inner inner;

        public LocalDate getDay() { return day; }
        public void setDay(LocalDate day) { this.day = day; }
        public LocalDateTime getMoment() { return moment; }
        public void setMoment(LocalDateTime moment) { this.moment = moment; }
        public long getWide() { return wide; }
        public void setWide(long wide) { this.wide = wide; }
        public List<String> getWords() { return words; }
        public void setWords(List<String> words) { this.words = words; }
        public Inner getInner() { return inner; }
        public void setInner(Inner inner) { this.inner = inner; }
    }

    /** A crawled page with crawled subpages: the type contains itself. */
    public static class Node {
        @LLMDescription("The page")
        private String url;
        @LLMDescription("Pages found under it")
        private List<Node> subpages;
        @LLMDescription("The one it came from")
        @LLMExample({"root"})
        @LLMContextIgnore
        private Node parent;

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public List<Node> getSubpages() { return subpages; }
        public void setSubpages(List<Node> subpages) { this.subpages = subpages; }
        public Node getParent() { return parent; }
        public void setParent(Node parent) { this.parent = parent; }
    }

    public static class Twin {
        private ai.redouble.nucleo.harness.schema.twins.Inner other;
        private Inner mine;

        public ai.redouble.nucleo.harness.schema.twins.Inner getOther() { return other; }
        public void setOther(ai.redouble.nucleo.harness.schema.twins.Inner other) { this.other = other; }
        public Inner getMine() { return mine; }
        public void setMine(Inner mine) { this.mine = mine; }
    }

    private static JsonNode jsonSchema(Class<?> type) throws IOException {
        return NucleoJsonSerializer.readTree(PojoResponseHandler.generateSchema(type).toJsonSchema());
    }

    private static JsonNode notation(Class<?> type) throws IOException {
        return NucleoJsonSerializer.readTree(PojoResponseHandler.generateSchema(type).toLLMSchema());
    }

    @Test
    void everyObjectNodeCarriesItsTypeNameAsTitle() throws IOException {
        JsonNode schema = jsonSchema(Outer.class);
        assertEquals("Outer", schema.path("title").asText(), "the root is named");
        assertEquals("Inner", schema.path("properties").path("inner").path("title").asText(), "a nested object is named too");
        assertEquals("Inner", notation(Outer.class).path("@fields").path("inner").path("@type").asText(), "the notation prints the same name as @type");
    }

    @Test
    void temporalFieldsPublishTheirFormatAndIntegersTheirBounds() throws IOException {
        JsonNode properties = jsonSchema(Outer.class).path("properties");
        assertEquals("string", properties.path("day").path("type").asText());
        assertEquals("date", properties.path("day").path("format").asText(), "a date is a string of a stated shape");
        assertEquals("date-time", properties.path("moment").path("format").asText());
        assertEquals(Long.MIN_VALUE, properties.path("wide").path("minimum").asLong(), "a long publishes its width");
        assertEquals(Long.MAX_VALUE, properties.path("wide").path("maximum").asLong());
        JsonNode count = properties.path("inner").path("properties").path("count");
        assertEquals(Integer.MIN_VALUE, count.path("minimum").asInt(), "an int publishes its narrower width");
        assertEquals(Integer.MAX_VALUE, count.path("maximum").asInt());
    }

    @Test
    void aCollectionOfScalarsPublishesItsItems() throws IOException {
        JsonNode words = jsonSchema(Outer.class).path("properties").path("words");
        assertEquals("array", words.path("type").asText());
        assertEquals("string", words.path("items").path("type").asText(), "an array of strings says so");
        assertEquals("array of string", notation(Outer.class).path("@fields").path("words").path("@type").asText());
    }

    @Test
    void aCyclicTypeRendersAsAReferenceWithDefinitionsOnTheRootOnly() throws IOException {
        JsonNode schema = jsonSchema(Node.class);
        JsonNode subpages = schema.path("properties").path("subpages");
        assertEquals("array", subpages.path("type").asText(), "an array of the referring type keeps its array wrapper");
        assertEquals("#/$defs/Node", subpages.path("items").path("$ref").asText());
        assertEquals("#/$defs/Node", schema.path("properties").path("parent").path("$ref").asText(), "a plain field is the reference itself");
        JsonNode definition = schema.path("$defs").path("Node");
        assertEquals("Node", definition.path("title").asText(), "the root carries the definition the references point at");
        assertFalse(definition.has("$defs"), "the definition does not carry its own $defs");
        assertEquals("#/$defs/Node", definition.path("properties").path("parent").path("$ref").asText(), "inside the definition the cycle is a reference again");
        assertFalse(jsonSchema(Outer.class).has("$defs"), "a schema with no cycle carries no $defs");
        JsonNode note = notation(Node.class);
        assertEquals("array of Node", note.path("@fields").path("subpages").path("@type").asText(), "the notation names the type it already described");
        assertEquals("Node", note.path("@fields").path("parent").path("@type").asText());
        assertFalse(note.path("@fields").path("parent").has("@fields"), "and does not describe it again");
    }

    @Test
    void aReferenceFieldKeepsItsOwnDescriptionExamplesAndKeywordsInBothRenderings() throws IOException {
        JsonNode parent = jsonSchema(Node.class).path("properties").path("parent");
        assertEquals("#/$defs/Node", parent.path("$ref").asText());
        assertEquals("The one it came from", parent.path("description").asText(), "the markers leave the description");
        assertEquals("root", parent.path("examples").get(0).asText(), "the examples are published beside the reference");
        assertTrue(parent.path(NucleoSchemaKeywords.CONTEXT_IGNORE).asBoolean(), "and so are the x-nucleo keywords");
        JsonNode overridden = NucleoJsonSerializer.readTree(PojoResponseHandler.generateSchema(Node.class).toJsonSchema(Map.of("parent", "$PARAM-PARENT")))
                .path("properties").path("parent");
        assertEquals("$PARAM-PARENT", overridden.path("description").asText(), "an override applies to a reference field too");
        JsonNode note = notation(Node.class).path("@fields").path("parent");
        assertEquals("The one it came from", note.path("@description").asText());
        assertEquals("root", note.path("@examples").get(0).asText());
    }

    @Test
    void twoTypesSharingOneSimpleNameAreRefused() {
        IllegalStateException refusal = assertThrows(IllegalStateException.class, () -> PojoResponseHandler.generateSchema(Twin.class));
        assertTrue(refusal.getMessage().contains("Two types named Inner"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains(Inner.class.getName()) && refusal.getMessage().contains("twins.Inner"), "both classes are named: " + refusal.getMessage());
    }

    @Test
    void theRequiredTokenAndTheExamplesLeaveTheDescriptionInBothRenderings() {
        FieldDescriptor field = new FieldDescriptor("size", "How big (REQUIRED) [Examples: 3, large]", "string");
        field.setRequired(true);
        field.setExamples(List.of("3", "large"));
        JsonNode note = NucleoJsonSerializer.valueToTree(field.toLLMSchemaNode());
        assertEquals("How big", note.path("@description").asText(), "the notation description is prose only");
        assertTrue(note.path("@required").asBoolean());
        assertEquals(3, note.path("@examples").get(0).asInt(), "a numeric example is a number");
        assertEquals("large", note.path("@examples").get(1).asText());
        JsonNode schema = NucleoJsonSerializer.valueToTree(field.toJsonSchemaNode(null));
        assertEquals("How big", schema.path("description").asText());
        assertEquals(3, schema.path("examples").get(0).asInt());
    }

    @Test
    void aDescriptionOverrideReplacesTheDescriptionInTheJsonSchemaForm() throws IOException {
        PojoDefinition definition = PojoResponseHandler.generateSchema(Outer.class);
        JsonNode schema = NucleoJsonSerializer.readTree(definition.toJsonSchema(Map.of("day", "$PARAM-DAY")));
        assertEquals("$PARAM-DAY", schema.path("properties").path("day").path("description").asText(), "the override stands in for the prose");
        assertEquals("At", schema.path("properties").path("moment").path("description").asText(), "fields without an override keep theirs");
    }
}
