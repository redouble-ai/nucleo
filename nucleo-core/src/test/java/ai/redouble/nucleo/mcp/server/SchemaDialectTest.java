/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.server;

import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Each dialect against the vendor rules it was written from. One canonical fixture carries
 * everything a rule can act on: a cycle, an optional property, an enum, a temporal format,
 * integer bounds, a harness keyword, a titled nested type, a closed root.
 *
 * <p>Sources, as of 2026-09-06: MCP specification 2025-11-25 and SEP-2106 (canonical);
 * OpenAI structured-outputs supported-schemas section (OPENAI_STRICT); Anthropic
 * structured-outputs JSON Schema limitations and Bedrock structured-output page
 * (ANTHROPIC_STRICT, BEDROCK_STRICT); Amazon Nova tool-use definition page (NOVA); Vertex
 * function-calling schema attributes (GEMINI).
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-06)
 */
public class SchemaDialectTest {
    private static final String CANONICAL_JSON = """
            {"type":"object","title":"Ask","additionalProperties":false,
             "properties":{
               "query":{"type":"string","description":"What to look up","%s":{"hint":"long"}},
               "limit":{"type":"integer","description":"How many","minimum":-2147483648,"maximum":2147483647},
               "when":{"type":"string","format":"date","description":"As YYYY-MM-DD"},
               "mode":{"type":"string","enum":["fast","deep"]},
               "tags":{"type":"array","items":{"type":"string"}},
               "node":{"$ref":"#/$defs/Node","description":"Where to start"}},
             "required":["query"],
             "$defs":{"Node":{"type":"object","title":"Node","description":"A node",
               "properties":{"label":{"type":"string"},"children":{"type":"array","items":{"$ref":"#/$defs/Node"}}},
               "required":["label"]}}}
            """.formatted(NucleoSchemaKeywords.SUMMARIZABLE);
    private static JsonNode canonical;

    @BeforeAll
    static void parse() throws Exception {
        canonical = NucleoJsonSerializer.readTree(CANONICAL_JSON);
    }

    private static JsonNode input(SchemaDialect dialect) {
        return dialect.inputSchema(canonical);
    }

    private static boolean mentions(JsonNode tree, String keyword) {
        return tree.toString().contains("\"" + keyword + "\"");
    }

    @Test
    void everyDialectHasItsOwnWireNameAndTheNamesRoundTrip() {
        // The wire name is the parameter value a request selects a dialect by, so it is a
        // contract: unique, resolvable back to its dialect, and an unknown one is nobody's.
        Set<String> seen = new HashSet<>();
        for (SchemaDialect dialect : SchemaDialect.values()) {
            assertTrue(seen.add(dialect.wireName()), "one name per dialect: " + dialect.wireName());
            assertSame(dialect, SchemaDialect.named(dialect.wireName()));
            assertTrue(SchemaDialect.wireNames().contains(dialect.wireName()));
        }
        assertEquals("canonical", SchemaDialect.CANONICAL.wireName());
        assertNull(SchemaDialect.named("klingon"), "a name that is no dialect resolves to nothing, never a throw");
        assertNull(SchemaDialect.named("CANONICAL"), "the wire is lowercase; an enum-cased name is not it");
    }

    @Test
    void aDialectThatStopsSpellingARuleStillStatesItInTheDescription() {
        // A dialect narrows the description, never the acceptance, so a rule whose keyword it
        // has no room for has to travel in the one keyword every dialect keeps. Without this a
        // caller can obey the schema it fetched and still be refused.
        JsonNode gemini = input(SchemaDialect.GEMINI);
        String limit = gemini.get("properties").get("limit").get("description").asText();
        assertTrue(limit.contains("Accepted range: -2147483648 to 2147483647."), limit);
        assertTrue(limit.startsWith("How many"), "what the author wrote comes first: " + limit);
        String when = gemini.get("properties").get("when").get("description").asText();
        assertTrue(when.contains("Must be an ISO-8601 date."), when);
        assertTrue(gemini.get("description").asText().contains("No other properties are accepted."),
                "Gemini cannot spell closure anywhere: " + gemini.get("description"));
        // Nova keeps every constraint below its root, so it has nothing to restate there. Its
        // one loss is closure at the root, and its root admits three keywords, none of them a
        // description; that rule cannot be carried on this path at all and is documented.
        JsonNode nova = input(SchemaDialect.NOVA);
        assertNotNull(nova.get("properties").get("limit").get("minimum"), "bounds survive below the root");
        assertEquals("How many", nova.get("properties").get("limit").get("description").asText(),
                "so nothing is added to what the author wrote");
        assertNull(nova.get("description"), "the root carries no description to say it in");
        JsonNode anthropic = input(SchemaDialect.ANTHROPIC_STRICT);
        assertTrue(anthropic.get("properties").get("limit").get("description").asText().contains("Accepted range"),
                "the strict subsets have no numeric constraints: " + anthropic.get("properties").get("limit"));
    }

    @Test
    void aBrokenCycleSaysThatTheShapeRepeats() {
        // The one rule FLAT drops. A caller that met a shapeless object where a tree continues
        // would have no way to know it may keep nesting.
        JsonNode flat = input(SchemaDialect.FLAT);
        JsonNode backReference = flat.get("properties").get("node").get("properties").get("children").get("items");
        assertEquals("Repeats the structure of Node, to any depth.", backReference.get("description").asText());
    }

    @Test
    void noDialectTouchesTheCanonicalTree() {
        String before = canonical.toString();
        for (SchemaDialect dialect : SchemaDialect.values()) {
            dialect.inputSchema(canonical);
            dialect.outputSchema(canonical);
        }
        assertEquals(before, canonical.toString());
    }

    @Test
    void canonicalIsTheIdentityOnACopy() {
        JsonNode rendered = input(SchemaDialect.CANONICAL);
        assertEquals(canonical, rendered);
        assertNotSame(canonical, rendered);
        assertEquals(canonical, SchemaDialect.CANONICAL.outputSchema(canonical));
    }

    @Test
    void flatHasNoReferenceAndKeepsTheName() {
        JsonNode flat = input(SchemaDialect.FLAT);
        assertFalse(mentions(flat, "$ref"), flat.toString());
        assertFalse(mentions(flat, "$defs"), flat.toString());
        JsonNode node = flat.get("properties").get("node");
        assertEquals("Node", node.get("title").asText(), "the inlined definition keeps its name");
        assertEquals("Where to start", node.get("description").asText(), "the use-site description wins over the definition's");
        JsonNode backReference = node.get("properties").get("children").get("items");
        assertEquals("object", backReference.get("type").asText());
        assertEquals("Node", backReference.get("title").asText(), "the broken cycle still says what it was: " + backReference);
        assertNull(backReference.get("properties"), "and nothing more");
        assertFalse(flat.toString().contains(NucleoSchemaKeywords.PREFIX), "harness keywords go with the references");
        assertNotNull(flat.get("properties").get("limit").get("minimum"), "flat narrows structure, not vocabulary");
    }

    @Test
    void openAiStrictClosesEveryObjectAndRequiresEveryProperty() {
        JsonNode strict = input(SchemaDialect.OPENAI_STRICT);
        assertFalse(strict.get("additionalProperties").asBoolean());
        assertFalse(strict.get("$defs").get("Node").get("additionalProperties").asBoolean(), "nested objects close too");
        List<String> required = new ArrayList<>();
        strict.get("required").forEach(n -> required.add(n.asText()));
        assertEquals(List.of("query", "limit", "when", "mode", "tags", "node"), required);
        JsonNode limit = strict.get("properties").get("limit");
        assertEquals("How many", limit.get("description").asText(), "the description stays where a reader looks");
        assertEquals("integer", limit.get("anyOf").get(0).get("type").asText());
        assertNotNull(limit.get("anyOf").get(0).get("minimum"), "OpenAI supports integer bounds");
        assertEquals("null", limit.get("anyOf").get(1).get("type").asText(), "an optional becomes nullable");
        assertNull(strict.get("properties").get("query").get("anyOf"), "a required one does not");
        JsonNode children = strict.get("$defs").get("Node").get("properties").get("children");
        assertEquals("#/$defs/Node", children.get("anyOf").get(0).get("items").get("$ref").asText(),
                "references and recursion are kept, inside the nullable wrapper of an optional: " + children);
        assertEquals("date", strict.get("properties").get("when").get("anyOf").get(0).get("format").asText());
        assertFalse(strict.toString().contains(NucleoSchemaKeywords.PREFIX));
    }

    @Test
    void openAiStrictOutputNarrowsVocabularyOnly() {
        JsonNode out = SchemaDialect.OPENAI_STRICT.outputSchema(canonical);
        assertEquals(1, out.get("required").size(), "output required-ness is untouched");
        assertNull(out.get("$defs").get("Node").get("additionalProperties"), "nothing is closed in an output");
        assertFalse(out.toString().contains(NucleoSchemaKeywords.PREFIX));
    }

    @Test
    void openAiStrictArgumentsDropTheNullsItsSpellingForcesAndOnlyThose() throws Exception {
        // A null is an omission only where the canonical schema declares an optional, followed
        // below the root through properties, items and $ref. Everything else is left for the
        // gate: a null on a required property is a missing one, a null under an undeclared
        // name is an undeclared property.
        JsonNode wire = NucleoJsonSerializer.readTree(
                "{\"query\":\"q\",\"limit\":null,\"when\":null,\"node\":{\"label\":\"a\",\"children\":null},\"tags\":[]}");
        JsonNode args = SchemaDialect.OPENAI_STRICT.arguments(wire, canonical);
        assertEquals("{\"query\":\"q\",\"node\":{\"label\":\"a\"},\"tags\":[]}", args.toString());
        assertTrue(wire.has("limit"), "the wire tree is not mutated");
        JsonNode requiredNull = NucleoJsonSerializer.readTree("{\"query\":null,\"limit\":null}");
        assertEquals("{\"query\":null}", SchemaDialect.OPENAI_STRICT.arguments(requiredNull, canonical).toString(),
                "a null on a required property stays for the gate to refuse");
        JsonNode undeclaredNull = NucleoJsonSerializer.readTree("{\"query\":\"q\",\"admin\":null,\"node\":{\"label\":\"a\",\"extra\":null}}");
        assertEquals("{\"query\":\"q\",\"admin\":null,\"node\":{\"label\":\"a\",\"extra\":null}}",
                SchemaDialect.OPENAI_STRICT.arguments(undeclaredNull, canonical).toString(),
                "a null under an undeclared name stays for the gate to refuse, at every depth");
        assertSame(wire, SchemaDialect.CANONICAL.arguments(wire, canonical), "every other dialect passes arguments through");
        assertSame(wire, SchemaDialect.FLAT.arguments(wire, canonical));
        assertSame(wire, SchemaDialect.NOVA.arguments(wire, canonical));
    }

    @Test
    void anthropicStrictClosesObjectsBreaksRecursionAndDropsBounds() {
        JsonNode strict = input(SchemaDialect.ANTHROPIC_STRICT);
        assertFalse(mentions(strict, "$ref"), "no recursion: " + strict);
        assertFalse(mentions(strict, "$defs"));
        JsonNode node = strict.get("properties").get("node");
        assertFalse(node.get("additionalProperties").asBoolean());
        assertEquals("Node", node.get("properties").get("children").get("items").get("title").asText());
        assertNull(strict.get("properties").get("limit").get("minimum"), "numeric constraints are unsupported");
        assertNull(strict.get("properties").get("limit").get("maximum"));
        assertEquals("date", strict.get("properties").get("when").get("format").asText(), "a listed format stays");
        assertEquals(1, strict.get("required").size(), "optional stays optional");
        assertFalse(strict.toString().contains(NucleoSchemaKeywords.PREFIX));
        JsonNode out = SchemaDialect.ANTHROPIC_STRICT.outputSchema(canonical);
        assertNull(out.get("properties").get("node").get("additionalProperties"), "outputs are not closed");
    }

    @Test
    void bedrockStrictIsTheSameRuleSetUnderItsOwnName() {
        assertEquals(input(SchemaDialect.ANTHROPIC_STRICT), input(SchemaDialect.BEDROCK_STRICT));
        assertEquals(SchemaDialect.ANTHROPIC_STRICT.outputSchema(canonical), SchemaDialect.BEDROCK_STRICT.outputSchema(canonical));
        assertNotEquals(SchemaDialect.ANTHROPIC_STRICT.wireName(), SchemaDialect.BEDROCK_STRICT.wireName());
    }

    @Test
    void novaKeepsThreeRootKeysAndNoTitles() {
        JsonNode nova = input(SchemaDialect.NOVA);
        List<String> rootKeys = new ArrayList<>();
        nova.fieldNames().forEachRemaining(rootKeys::add);
        assertEquals(Set.of("type", "properties", "required"), new HashSet<>(rootKeys), rootKeys.toString());
        assertFalse(mentions(nova, "title"), nova.toString());
        assertFalse(mentions(nova, "$ref"));
        assertEquals("Where to start", nova.get("properties").get("node").get("description").asText(), "nested descriptions are allowed");
        assertFalse(nova.toString().contains(NucleoSchemaKeywords.PREFIX));
    }

    @Test
    void geminiRenamesReferencesAndKeepsItsAttributeList() {
        JsonNode gemini = input(SchemaDialect.GEMINI);
        assertFalse(mentions(gemini, "$ref"), gemini.toString());
        assertFalse(mentions(gemini, "$defs"));
        assertEquals("#/defs/Node", gemini.get("properties").get("node").get("ref").asText());
        assertEquals("#/defs/Node", gemini.get("defs").get("Node").get("properties").get("children").get("items").get("ref").asText(),
                "recursion stays as a reference, which Gemini follows to depth two");
        assertFalse(mentions(gemini, "title"));
        assertFalse(mentions(gemini, "minimum"));
        assertFalse(mentions(gemini, "additionalProperties"));
        assertNull(gemini.get("properties").get("when").get("format"), "date is not a Gemini string format");
        assertEquals(2, gemini.get("properties").get("mode").get("enum").size());
        assertFalse(gemini.toString().contains(NucleoSchemaKeywords.PREFIX));
    }
}
