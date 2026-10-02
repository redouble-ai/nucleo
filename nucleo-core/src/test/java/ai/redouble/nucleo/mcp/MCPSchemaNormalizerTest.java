/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp;

import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;

import java.io.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins {@link MCPSchemaNormalizer#normalize}: real MCP servers hand back schemas full of
 * {@code $ref}s and draft-specific keywords that provider tool APIs reject. The contract:
 * a local {@code $defs} reference inlines; an external, cyclic, or unresolvable reference
 * collapses to a permissive {@code {"type":"object"}} rather than failing the tool;
 * unsupported keywords are stripped; and the caller's tree is never mutated.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-31)
 */
public class MCPSchemaNormalizerTest {

    private static JsonNode read(String json) throws IOException {
        return NucleoJsonSerializer.readTree(json);
    }

    @Test
    void localRefInlinesItsDefinition() throws IOException {
        JsonNode raw = read("""
                {"type":"object",
                 "properties":{"who":{"$ref":"#/$defs/person"}},
                 "$defs":{"person":{"type":"string","description":"a name"}}}""");
        JsonNode normalized = MCPSchemaNormalizer.normalize(raw);
        assertEquals("string", normalized.path("properties").path("who").path("type").asText(),
                "the definition body replaces the reference");
        assertFalse(normalized.toString().contains("$ref"), "no reference survives normalization");
    }

    @Test
    void externalRefCollapsesToObject_insteadOfFailingTheTool() throws IOException {
        JsonNode raw = read("""
                {"type":"object",
                 "properties":{"cfg":{"$ref":"https://example.com/schemas/config.json"}}}""");
        JsonNode normalized = MCPSchemaNormalizer.normalize(raw);
        assertEquals("object", normalized.path("properties").path("cfg").path("type").asText(),
                "an unreachable definition degrades to a permissive object");
    }

    @Test
    void cyclicRefKeepsItsDefinition() throws IOException {
        JsonNode raw = read("""
                {"type":"object",
                 "properties":{"node":{"$ref":"#/$defs/node"}},
                 "$defs":{"node":{"type":"object","properties":{"next":{"$ref":"#/$defs/node"}}}}}""");
        JsonNode normalized = MCPSchemaNormalizer.normalize(raw);
        JsonNode node = normalized.path("properties").path("node");
        assertEquals("object", node.path("type").asText(), "the first occurrence is inlined in full");
        assertEquals("#/$defs/node", node.path("properties").path("next").path("$ref").asText(),
                "the reference that closes the cycle stays a reference: " + normalized);
        assertEquals("#/$defs/node", normalized.path("$defs").path("node").path("properties").path("next").path("$ref").asText(),
                "and its definition is kept for it to resolve against, terminating on itself");
    }

    @Test
    void legacyDefinitionsSectionIsReadAndDropped() throws IOException {
        JsonNode raw = read("""
                {"type":"object",
                 "properties":{"who":{"$ref":"#/definitions/person"}},
                 "definitions":{"person":{"type":"string"}}}""");
        JsonNode normalized = MCPSchemaNormalizer.normalize(raw);
        assertEquals("string", normalized.path("properties").path("who").path("type").asText());
        assertFalse(normalized.has("definitions"));
        assertFalse(normalized.has("$defs"), "nothing cyclic, nothing to keep: " + normalized);
    }

    @Test
    void unresolvedLocalRefCollapsesToObject() throws IOException {
        JsonNode raw = read("""
                {"type":"object","properties":{"x":{"$ref":"#/$defs/missing"}}}""");
        JsonNode normalized = MCPSchemaNormalizer.normalize(raw);
        assertEquals("object", normalized.path("properties").path("x").path("type").asText());
    }

    @Test
    void unsupportedKeywordsAreStripped_supportedOnesSurvive() throws IOException {
        JsonNode raw = read("""
                {"type":"object",
                 "unevaluatedProperties":false,
                 "properties":{"q":{"type":"string","description":"kept"}}}""");
        JsonNode normalized = MCPSchemaNormalizer.normalize(raw);
        assertFalse(normalized.has("unevaluatedProperties"),
                "keywords provider tool APIs reject are removed");
        assertEquals("kept", normalized.path("properties").path("q").path("description").asText());
    }

    @Test
    void theCallersTreeIsNeverMutated() throws IOException {
        JsonNode raw = read("""
                {"type":"object",
                 "properties":{"who":{"$ref":"#/$defs/person"}},
                 "$defs":{"person":{"type":"string"}}}""");
        String before = raw.toString();
        MCPSchemaNormalizer.normalize(raw);
        assertEquals(before, raw.toString(), "normalization works on a copy - the descriptor stays pristine");
    }
}
