/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

import ai.redouble.nucleo.harness.conversation.*;
import org.junit.jupiter.api.*;

import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PojoResponseHandler#generateSchema(Class)} and the resulting
 * {@link PojoDefinition} / {@link FieldDescriptor} tree.
 *
 * <p>Covers the schema walker's handling of scalar fields, annotated fields,
 * nested POJOs, collections, maps, arrays, deep nesting, and the critical
 * JDK-leaf regression guard: URL/Path/Optional etc. must not be recursed into.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-14)
 */
public class PojoDefinitionTest {

    // ========================= Fixtures =========================

    @LLMDescription("A flat POJO with scalar fields")
    public static class FlatPojo {
        @LLMRequired
        @LLMDescription("The user's name")
        private String name;

        @LLMDescription("The user's age")
        private int age;

        @LLMDescription("Account balance")
        private long balance;

        private double score;
        private boolean active;
        private LocalDate joined;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getAge() { return age; }
        public void setAge(int age) { this.age = age; }
        public long getBalance() { return balance; }
        public void setBalance(long balance) { this.balance = balance; }
        public double getScore() { return score; }
        public void setScore(double score) { this.score = score; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
        public LocalDate getJoined() { return joined; }
        public void setJoined(LocalDate joined) { this.joined = joined; }
    }

    public static class Address {
        @LLMDescription("Street address line")
        private String street;
        @LLMDescription("City name")
        private String city;

        public String getStreet() { return street; }
        public void setStreet(String street) { this.street = street; }
        public String getCity() { return city; }
        public void setCity(String city) { this.city = city; }
    }

    public static class PersonWithAddress {
        @LLMRequired
        private String name;
        @LLMDescription("Home address")
        private Address home;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Address getHome() { return home; }
        public void setHome(Address home) { this.home = home; }
    }

    public static class ListOfNestedPojo {
        private List<Address> addresses;
        public List<Address> getAddresses() { return addresses; }
        public void setAddresses(List<Address> addresses) { this.addresses = addresses; }
    }

    public static class ListOfString {
        private List<String> tags;
        public List<String> getTags() { return tags; }
        public void setTags(List<String> tags) { this.tags = tags; }
    }

    public static class MapOfNestedPojo {
        private Map<String, Address> byLabel;
        public Map<String, Address> getByLabel() { return byLabel; }
        public void setByLabel(Map<String, Address> byLabel) { this.byLabel = byLabel; }
    }

    public static class ArrayOfNestedPojo {
        private Address[] items;
        public Address[] getItems() { return items; }
        public void setItems(Address[] items) { this.items = items; }
    }

    // Three-level deep nesting: Level1.b -> Level2.c -> Level3.leaf
    public static class Level1 {
        private Level2 b;
        public Level2 getB() { return b; }
        public void setB(Level2 b) { this.b = b; }
    }
    public static class Level2 {
        private Level3 c;
        public Level3 getC() { return c; }
        public void setC(Level3 c) { this.c = c; }
    }
    public static class Level3 {
        @LLMDescription("Deepest leaf")
        private String leaf;
        public String getLeaf() { return leaf; }
        public void setLeaf(String leaf) { this.leaf = leaf; }
    }

    /**
     * The critical regression-guard fixture: every field here is a JDK value
     * type the schema walker must treat as a leaf. If any of these sprout a
     * nested {@link PojoDefinition}, the package filter in
     * {@code NucleoJsonSerializer.isComposite} has broken.
     */
    public static class JdkLeafFields {
        private URL websiteUrl;
        private URI identifier;
        private Path filePath;
        private Optional<String> maybeValue;
        private java.sql.Timestamp lastModified;

        public URL getWebsiteUrl() { return websiteUrl; }
        public void setWebsiteUrl(URL websiteUrl) { this.websiteUrl = websiteUrl; }
        public URI getIdentifier() { return identifier; }
        public void setIdentifier(URI identifier) { this.identifier = identifier; }
        public Path getFilePath() { return filePath; }
        public void setFilePath(Path filePath) { this.filePath = filePath; }
        public Optional<String> getMaybeValue() { return maybeValue; }
        public void setMaybeValue(Optional<String> maybeValue) { this.maybeValue = maybeValue; }
        public java.sql.Timestamp getLastModified() { return lastModified; }
        public void setLastModified(java.sql.Timestamp lastModified) { this.lastModified = lastModified; }
    }

    public static class EmptyPojo {
    }

    // ========================= Flat POJO =========================

    @Test
    public void flatPojoPicksUpClassAndFieldDescriptions() {
        PojoDefinition def = PojoResponseHandler.generateSchema(FlatPojo.class);

        assertEquals("FlatPojo", def.getClassName());
        assertEquals("A flat POJO with scalar fields", def.getDescription());

        Map<String, FieldDescriptor> fields = def.getFields();
        assertEquals(6, fields.size(), "all six declared fields should be present: " + fields.keySet());

        FieldDescriptor name = fields.get("name");
        assertNotNull(name);
        assertEquals("string", name.getType());
        assertTrue(name.getDescription().contains("The user's name"));
        assertTrue(name.getDescription().contains("(REQUIRED)"),
            "@LLMRequired should surface as a token in the description: " + name.getDescription());
    }

    @Test
    public void flatPojoMapsScalarTypeNames() {
        PojoDefinition def = PojoResponseHandler.generateSchema(FlatPojo.class);
        Map<String, FieldDescriptor> fields = def.getFields();

        assertEquals("string", fields.get("name").getType());
        assertEquals("integer", fields.get("age").getType());
        assertEquals("long", fields.get("balance").getType());
        assertEquals("number", fields.get("score").getType());
        assertEquals("boolean", fields.get("active").getType());
        assertEquals("date", fields.get("joined").getType(),
            "LocalDate maps to 'date'");
    }

    @Test
    public void unannotatedFieldsStillGetDefaultDescription() {
        PojoDefinition def = PojoResponseHandler.generateSchema(FlatPojo.class);
        FieldDescriptor score = def.getFields().get("score");
        assertNotNull(score.getDescription(), "unannotated fields still get a default description");
        assertFalse(score.getDescription().isEmpty());
    }

    // ========================= Nested POJOs =========================

    @Test
    public void nestedPojoFieldRecursesIntoInnerDefinition() {
        PojoDefinition def = PojoResponseHandler.generateSchema(PersonWithAddress.class);

        FieldDescriptor home = def.getFields().get("home");
        assertNotNull(home);
        assertNotNull(home.getDefinition(),
            "nested POJO field should produce a nested PojoDefinition");
        assertEquals("Address", home.getDefinition().getClassName());

        Map<String, FieldDescriptor> addressFields = home.getDefinition().getFields();
        assertEquals(2, addressFields.size());
        assertEquals("string", addressFields.get("street").getType());
        assertEquals("string", addressFields.get("city").getType());
    }

    @Test
    public void deeplyNestedPojoRecursesAllLevels() {
        PojoDefinition def = PojoResponseHandler.generateSchema(Level1.class);

        FieldDescriptor b = def.getFields().get("b");
        assertNotNull(b.getDefinition(), "Level1.b should recurse into Level2");
        assertEquals("Level2", b.getDefinition().getClassName());

        FieldDescriptor c = b.getDefinition().getFields().get("c");
        assertNotNull(c.getDefinition(), "Level2.c should recurse into Level3");
        assertEquals("Level3", c.getDefinition().getClassName());

        FieldDescriptor leaf = c.getDefinition().getFields().get("leaf");
        assertEquals("string", leaf.getType());
        assertNull(leaf.getDefinition(), "Level3.leaf is a String - no nested definition");
        assertTrue(leaf.getDescription().contains("Deepest leaf"));
    }

    // ========================= Collections =========================

    @Test
    public void listOfNestedPojoHasElementDefinition() {
        PojoDefinition def = PojoResponseHandler.generateSchema(ListOfNestedPojo.class);

        FieldDescriptor addresses = def.getFields().get("addresses");
        assertTrue(addresses.getType().startsWith("List<"),
            "List<Pojo> type should start with 'List<': " + addresses.getType());
        assertTrue(addresses.getType().contains("Address"));
        assertNotNull(addresses.getDefinition(),
            "List<Pojo> should carry a nested definition for the element type");
        assertEquals("Address", addresses.getDefinition().getClassName());
    }

    @Test
    public void listOfStringHasNoElementDefinition() {
        PojoDefinition def = PojoResponseHandler.generateSchema(ListOfString.class);

        FieldDescriptor tags = def.getFields().get("tags");
        assertTrue(tags.getType().contains("string"),
            "List<String> type should mention 'string': " + tags.getType());
        assertNull(tags.getDefinition(),
            "List<String> should NOT carry a nested definition - String is a leaf");
    }

    @Test
    public void mapFieldDescribesKeyAndValueTypes() {
        PojoDefinition def = PojoResponseHandler.generateSchema(MapOfNestedPojo.class);

        // JSON property names are snake_case
        FieldDescriptor byLabel = def.getFields().get("by_label");
        assertNotNull(byLabel, "field should be present under snake_case name: " + def.getFields().keySet());
        assertTrue(byLabel.getType().startsWith("map"),
            "Map field type should describe it as a map: " + byLabel.getType());
    }

    @Test
    public void arrayOfNestedPojoHasComponentDefinition() {
        PojoDefinition def = PojoResponseHandler.generateSchema(ArrayOfNestedPojo.class);

        FieldDescriptor items = def.getFields().get("items");
        assertEquals("array", items.getType());
        assertNotNull(items.getDefinition(),
            "array-of-Pojo should carry a nested definition for the component type");
        assertEquals("Address", items.getDefinition().getClassName());
    }

    // ========================= JDK leaf regression guard =========================

    /**
     * The most important test in this file. This is the specific hole the
     * {@code java.*}/{@code javax.*}/{@code jakarta.*} package filter in
     * {@code NucleoJsonSerializer.isComposite} was added to close. If a
     * future edit removes or weakens that filter, any of these assertions
     * will fail and surface the regression before it reaches production.
     */
    @Test
    public void jdkValueTypesAreTreatedAsLeavesNotCompositeFields() {
        PojoDefinition def = PojoResponseHandler.generateSchema(JdkLeafFields.class);

        // Field names are serialized snake_case
        for (String fieldName : List.of("website_url", "identifier", "file_path", "maybe_value", "last_modified")) {
            FieldDescriptor field = def.getFields().get(fieldName);
            assertNotNull(field, "field " + fieldName + " should be in the schema: " + def.getFields().keySet());
            assertNull(field.getDefinition(),
                "JDK value type '" + fieldName + "' must NOT have a nested PojoDefinition - " +
                "the schema walker would produce garbage if it recursed into " +
                "java.net/java.nio/java.util/java.sql internals. " +
                "Check NucleoJsonSerializer.isComposite() for a regression.");
        }
    }

    // ========================= JSON Schema output =========================

    @Test
    public void toJsonSchemaOutputsRequiredArrayFromLLMRequired() {
        PojoDefinition def = PojoResponseHandler.generateSchema(FlatPojo.class);
        String jsonSchema = def.toJsonSchema();

        assertTrue(jsonSchema.contains("\"type\" : \"object\""),
            "top-level schema must be object: " + jsonSchema);
        assertTrue(jsonSchema.contains("\"properties\""),
            "schema must have properties: " + jsonSchema);
        assertTrue(jsonSchema.contains("\"required\""),
            "schema must have required array: " + jsonSchema);
        assertTrue(jsonSchema.contains("\"name\""), "required name field should appear");
    }

    @Test
    public void toJsonSchemaIncludesNestedObjectProperties() {
        PojoDefinition def = PojoResponseHandler.generateSchema(PersonWithAddress.class);
        String jsonSchema = def.toJsonSchema();

        assertTrue(jsonSchema.contains("\"street\""),
            "nested address.street should appear in JSON Schema: " + jsonSchema);
        assertTrue(jsonSchema.contains("\"city\""));
    }

    @Test
    public void toLLMSchemaMentionsClassName() {
        PojoDefinition def = PojoResponseHandler.generateSchema(FlatPojo.class);
        String llmSchema = def.toLLMSchema();

        assertTrue(llmSchema.contains("FlatPojo"));
        assertTrue(llmSchema.contains("@fields"),
            "toLLMSchema should wrap fields under @fields: " + llmSchema);
    }

    // ========================= Edge cases =========================

    @Test
    public void emptyPojoProducesEmptyFieldsMap() {
        PojoDefinition def = PojoResponseHandler.generateSchema(EmptyPojo.class);
        assertEquals("EmptyPojo", def.getClassName());
        assertNotNull(def.getFields());
        assertTrue(def.getFields().isEmpty(), "empty POJO should have no fields");
    }
}
