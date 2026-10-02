/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The one mapper behind {@link NucleoJsonSerializer}, as the package documentation states it:
 * snake_case on the wire, nulls omitted on write, an empty bean written as {@code {}}, unknown
 * properties and a null for a primitive tolerated on read, and the lenient syntax a model
 * produces (comments, trailing commas, single quotes, unquoted names, any backslash escape,
 * NaN and the infinities) accepted by {@link NucleoJsonSerializer#parse}. Also the plain entry points: pretty against compact,
 * {@code readTree}, {@code convert}, {@code valueToTree}, and the refusals of {@code parse}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class NucleoJsonSerializerMapperContractTest {

    public static class Shape {
        private String firstName;
        private Integer count;
        private int primitiveCount;
        private List<String> tags;

        public String getFirstName() { return firstName; }
        public void setFirstName(String firstName) { this.firstName = firstName; }
        public Integer getCount() { return count; }
        public void setCount(Integer count) { this.count = count; }
        public int getPrimitiveCount() { return primitiveCount; }
        public void setPrimitiveCount(int primitiveCount) { this.primitiveCount = primitiveCount; }
        public List<String> getTags() { return tags; }
        public void setTags(List<String> tags) { this.tags = tags; }
    }

    public static class Nothing {
    }

    @Test
    void propertiesTravelInSnakeCaseAndNullsAreOmitted() throws IOException {
        Shape shape = new Shape();
        shape.setFirstName("Ada");
        String json = NucleoJsonSerializer.writeCompact(shape);
        assertEquals("{\"first_name\":\"Ada\",\"primitive_count\":0}", json, "camelCase becomes snake_case; a null field is left out");
        Shape back = NucleoJsonSerializer.parse("{\"first_name\":\"Ada\",\"count\":2}", Shape.class);
        assertEquals("Ada", back.getFirstName(), "snake_case reads back into the camelCase field");
        assertEquals(2, back.getCount());
    }

    @Test
    void anEmptyBeanIsAnEmptyObject() {
        assertEquals("{}", NucleoJsonSerializer.writeCompact(new Nothing()), "a bean with nothing to say is {} and never a failure");
    }

    @Test
    void unknownPropertiesAndNullPrimitivesAreTolerated() throws IOException {
        Shape shape = NucleoJsonSerializer.parse("{\"first_name\":\"Ada\",\"never_declared\":true,\"primitive_count\":null}", Shape.class);
        assertEquals("Ada", shape.getFirstName(), "a property the class does not declare is ignored");
        assertEquals(0, shape.getPrimitiveCount(), "a null for a primitive leaves the default");
    }

    @Test
    void theSyntaxAModelProducesIsAccepted() throws IOException {
        Shape shape = NucleoJsonSerializer.parse("{first_name: 'Ada', // a comment\n count: 3, tags: ['x', 'y',],}", Shape.class);
        assertEquals("Ada", shape.getFirstName(), "unquoted names and single quotes");
        assertEquals(3, shape.getCount(), "a Java comment inside the document");
        assertEquals(List.of("x", "y"), shape.getTags(), "trailing commas");
        Map<?, ?> nan = NucleoJsonSerializer.parse("{\"v\": NaN, \"up\": Infinity, \"down\": -Infinity}", Map.class);
        assertTrue(((Double) nan.get("v")).isNaN(), "NaN is a number the reader accepts");
        assertEquals(Double.POSITIVE_INFINITY, nan.get("up"), "and so are the infinities");
        assertEquals(Double.NEGATIVE_INFINITY, nan.get("down"));
        assertEquals("a-b", NucleoJsonSerializer.parse("{\"first_name\": \"a\\-b\"}", Shape.class).getFirstName(),
                "a backslash before any character escapes that character");
    }

    @Test
    void prettyAndCompactCarryTheSameData() throws IOException {
        Shape shape = new Shape();
        shape.setFirstName("Ada");
        shape.setTags(List.of("x"));
        String pretty = NucleoJsonSerializer.write(shape);
        String compact = NucleoJsonSerializer.writeCompact(shape);
        assertTrue(pretty.contains("\n"), "write is pretty-printed");
        assertFalse(compact.contains("\n"), "writeCompact carries no formatting whitespace");
        assertEquals(NucleoJsonSerializer.readTree(pretty), NucleoJsonSerializer.readTree(compact), "the same tree either way");
    }

    @Test
    void treeAndConversionEntryPointsUseTheSameMapper() {
        Shape shape = new Shape();
        shape.setFirstName("Ada");
        JsonNode tree = NucleoJsonSerializer.valueToTree(shape);
        assertEquals("Ada", tree.path("first_name").asText(), "valueToTree renders with the wire naming");
        Shape converted = NucleoJsonSerializer.convert(Map.of("first_name", "Grace", "count", 7), Shape.class);
        assertEquals("Grace", converted.getFirstName(), "convert reads a map through the same naming");
        assertEquals(7, converted.getCount());
        assertTrue(NucleoJsonSerializer.createObjectNode().isObject());
        assertTrue(NucleoJsonSerializer.createArrayNode().isArray());
    }

    @Test
    void parseRefusesNothingAndNotJson() {
        IOException empty = assertThrows(IOException.class, () -> NucleoJsonSerializer.parse("  ", Shape.class));
        assertEquals("JSON is empty or null", empty.getMessage());
        IOException bad = assertThrows(IOException.class, () -> NucleoJsonSerializer.parse("{\"first_name\": }", Shape.class));
        assertTrue(bad.getMessage().startsWith("Failed to parse JSON"), "a mapping failure names itself: " + bad.getMessage());
    }

    @Test
    void parseRepairsRawControlCharactersInsideStrings() throws IOException {
        Shape shape = NucleoJsonSerializer.parse("{\"first_name\": \"two\nlines\"}", Shape.class);
        assertEquals("two\nlines", shape.getFirstName(), "a raw newline inside a string value is escaped rather than refused");
    }

    @Test
    void parseRepairsTypographicQuotesOutsideStringsAndKeepsThemInside() throws IOException {
        Shape shape = NucleoJsonSerializer.parse("{“first_name”: “not ‘plain’ — curly”}", Shape.class);
        assertEquals("not ‘plain’ — curly", shape.getFirstName(),
                "curly quotes around a key or a value read as quotes; punctuation inside the value stays as written");
    }
}
