/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

import ai.redouble.nucleo.harness.conversation.*;
import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What each annotation of this package changes, as its javadoc states: {@code @LLMDescription}
 * on a class or a field is the description in both renderings, and a field without one is
 * described as {@code Field <name>}; {@code @LLMRequired} marks the field required in both
 * renderings and is what {@code validateRequiredFields} checks, null or blank refused and an
 * empty collection accepted; {@code @LLMExample} publishes the examples; a static, transient or
 * {@code @JsonIgnore} field is not in the schema while {@code @JsonProperty} renames one;
 * {@code @LLMContextIgnore} keeps its field in the schema.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class SchemaAnnotationsTest {

    @LLMDescription("A described class")
    public static class Described {
        @LLMRequired
        @LLMDescription("Must be there")
        private String needed;
        private String plain;
        @LLMExample({"one", "two"})
        @LLMDescription("With examples")
        private String sampled;
        @JsonIgnore
        private String hidden;
        private transient String scratch;
        private static String shared;
        @JsonProperty("renamed_on_the_wire")
        private String original;
        @LLMRequired
        @LLMDescription("May be empty")
        private List<String> items;

        public String getNeeded() { return needed; }
        public void setNeeded(String needed) { this.needed = needed; }
        public String getPlain() { return plain; }
        public void setPlain(String plain) { this.plain = plain; }
        public String getSampled() { return sampled; }
        public void setSampled(String sampled) { this.sampled = sampled; }
        public String getHidden() { return hidden; }
        public void setHidden(String hidden) { this.hidden = hidden; }
        public String getOriginal() { return original; }
        public void setOriginal(String original) { this.original = original; }
        public List<String> getItems() { return items; }
        public void setItems(List<String> items) { this.items = items; }
    }

    public static class Undescribed {
        private String value;

        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }

    private static JsonNode notation(Class<?> type) throws IOException {
        return NucleoJsonSerializer.readTree(PojoResponseHandler.generateSchema(type).toLLMSchema());
    }

    private static JsonNode jsonSchema(Class<?> type) throws IOException {
        return NucleoJsonSerializer.readTree(PojoResponseHandler.generateSchema(type).toJsonSchema());
    }

    @Test
    void descriptionsComeFromTheAnnotationOrAreDefaulted() throws IOException {
        assertEquals("A described class", notation(Described.class).path("@description").asText(), "the class description");
        assertEquals("A described class", jsonSchema(Described.class).path("description").asText());
        assertEquals("Schema of Undescribed", notation(Undescribed.class).path("@description").asText(), "a class without one is named");
        assertEquals("Field plain", notation(Described.class).path("@fields").path("plain").path("@description").asText(), "a field without one is named");
        assertEquals("Must be there", jsonSchema(Described.class).path("properties").path("needed").path("description").asText());
    }

    @Test
    void requiredIsMarkedInBothRenderingsAndCheckedOnTheInstance() throws IOException {
        assertTrue(notation(Described.class).path("@fields").path("needed").path("@required").asBoolean());
        assertFalse(notation(Described.class).path("@fields").path("plain").has("@required"));
        List<String> required = new ArrayList<>();
        jsonSchema(Described.class).path("required").forEach(node -> required.add(node.asText()));
        assertEquals(List.of("needed", "items"), required);
        Described instance = new Described();
        instance.setItems(List.of());
        assertFalse(PojoResponseHandler.validateRequiredFields(instance), "a null required field fails");
        instance.setNeeded("   ");
        assertFalse(PojoResponseHandler.validateRequiredFields(instance), "a blank required string fails");
        instance.setNeeded("present");
        assertTrue(PojoResponseHandler.validateRequiredFields(instance), "an empty required collection is a valid answer");
    }

    @Test
    void examplesArePublishedInBothRenderings() throws IOException {
        JsonNode note = notation(Described.class).path("@fields").path("sampled");
        assertEquals("With examples", note.path("@description").asText(), "the examples leave the description");
        assertEquals("one", note.path("@examples").get(0).asText());
        JsonNode schema = jsonSchema(Described.class).path("properties").path("sampled");
        assertEquals("two", schema.path("examples").get(1).asText());
    }

    @Test
    void jacksonAnnotationsAndModifiersDecideWhatIsInTheSchema() throws IOException {
        JsonNode fields = notation(Described.class).path("@fields");
        assertFalse(fields.has("hidden"), "@JsonIgnore keeps a field out");
        assertFalse(fields.has("scratch"), "transient keeps a field out");
        assertFalse(fields.has("shared"), "static keeps a field out");
        assertTrue(fields.has("renamed_on_the_wire") && !fields.has("original"), "@JsonProperty names the field");
    }
}
