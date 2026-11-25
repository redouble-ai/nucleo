/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp;

import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the {@link MCPResultWalker} pure walk behavior: shape preservation and promotion
 * of over-threshold sub-objects. The walker summarizes nothing; the summary of a long
 * text leaf is made by the serializer when the artifact is written for a model, and kept
 * under the {@code data} field's name followed by the leaf's escaped JSON pointer.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-05-09)
 */
public class MCPResultWalkerTest {

    private static final MCPHandle HANDLE = new MCPHandle("test");

    private static String repeat(String s, int times) {
        StringBuilder sb = new StringBuilder(s.length() * times);
        for (int i = 0; i < times; i++) sb.append(s);
        return sb.toString();
    }

    @Test
    public void smallFlatObjectPassesThroughVerbatim() throws Exception {
        ObjectNode raw = NucleoJsonSerializer.createObjectNode();
        raw.put("name", "Alice");
        raw.put("count", 42);
        raw.put("active", true);

        MCPArtifact artifact = MCPResultWalker.walk(raw, HANDLE, "tool_a");

        assertEquals("test", artifact.getMcpHandle());
        assertEquals("tool_a", artifact.getToolName());
        Map<String, Object> data = artifact.getData();
        assertEquals("Alice", data.get("name"));
        assertEquals(42, ((Number) data.get("count")).intValue());
        assertEquals(true, data.get("active"));
        NucleoJsonSerializer.writeSummarized(artifact);
        assertTrue(artifact.getSummaries().all().isEmpty(), "no long leaf, so no summary is ever kept");
    }

    @Test
    public void theWalkerKeepsALongLeafWholeAndTheSerializerSummarizesIt() throws Exception {
        String longText = repeat("x", 1500);
        ObjectNode raw = NucleoJsonSerializer.createObjectNode();
        raw.put("body", longText);

        MCPArtifact artifact = MCPResultWalker.walk(raw, HANDLE, "tool_a");

        assertEquals(longText, artifact.getData().get("body"));
        assertTrue(artifact.getSummaries().all().isEmpty(), "the walker makes no summary");
        NucleoJsonSerializer.writeSummarized(artifact);
        String summary = artifact.getSummaries().get("data/body");
        assertNotNull(summary, "the serializer keeps the leaf's summary at data/body");
        assertNotEquals(longText, summary);
        assertEquals(longText, artifact.getData().get("body"), "and the full text stays in the artifact");
    }

    @Test
    public void shortStringLeafIsNeverSummarized() throws Exception {
        ObjectNode raw = NucleoJsonSerializer.createObjectNode();
        raw.put("body", "short text");

        MCPArtifact artifact = MCPResultWalker.walk(raw, HANDLE, "tool_a");

        assertEquals("short text", artifact.getData().get("body"));
        NucleoJsonSerializer.writeSummarized(artifact);
        assertNull(artifact.getSummaries().get("data/body"));
    }

    @Test
    public void largeSubObjectPromotedToNestedArtifact() throws Exception {
        // Build a sub-object whose serialized form exceeds the 2KB promotion threshold.
        ObjectNode big = NucleoJsonSerializer.createObjectNode();
        big.put("title", "X");
        big.put("payload", repeat("a", 2500));
        ObjectNode raw = NucleoJsonSerializer.createObjectNode();
        raw.set("nested", big);
        raw.put("sibling", "small");

        MCPArtifact artifact = MCPResultWalker.walk(raw, HANDLE, "tool_a");

        Object nestedValue = artifact.getData().get("nested");
        assertInstanceOf(MCPArtifact.class, nestedValue,
                "over-threshold sub-object should be promoted to nested MCPArtifact");
        MCPArtifact nested = (MCPArtifact) nestedValue;
        assertEquals("test", nested.getMcpHandle());
        assertEquals("tool_a", nested.getToolName());
        assertEquals("X", nested.getData().get("title"));
        assertEquals("small", artifact.getData().get("sibling"));
        NucleoJsonSerializer.writeSummarized(artifact);
        // A long leaf inside the nested artifact is that artifact's own summary
        assertNotNull(nested.getSummaries().get("data/payload"),
                "a promoted sub-object keeps its leaf's summary itself, keyed from its own root");
        assertNull(artifact.getSummaries().get("data/nested/payload"), "and the parent keeps none for it");
    }

    @Test
    public void smallSubObjectStaysInline() throws Exception {
        ObjectNode small = NucleoJsonSerializer.createObjectNode();
        small.put("k", "v");
        ObjectNode raw = NucleoJsonSerializer.createObjectNode();
        raw.set("nested", small);

        MCPArtifact artifact = MCPResultWalker.walk(raw, HANDLE, "tool_a");

        Object nestedValue = artifact.getData().get("nested");
        assertInstanceOf(Map.class, nestedValue,
                "under-threshold sub-object should stay inline as a Map");
        @SuppressWarnings("unchecked")
        Map<String, Object> inlined = (Map<String, Object>) nestedValue;
        assertEquals("v", inlined.get("k"));
    }

    @Test
    public void arrayElementsWalkedWithIndexPaths() throws Exception {
        ObjectNode raw = NucleoJsonSerializer.createObjectNode();
        var arr = NucleoJsonSerializer.createArrayNode();
        ObjectNode el0 = NucleoJsonSerializer.createObjectNode();
        el0.put("text", repeat("y", 1500));
        arr.add(el0);
        ObjectNode el1 = NucleoJsonSerializer.createObjectNode();
        el1.put("text", "short");
        arr.add(el1);
        raw.set("items", arr);

        MCPArtifact artifact = MCPResultWalker.walk(raw, HANDLE, "tool_a");

        NucleoJsonSerializer.writeSummarized(artifact);
        assertNotNull(artifact.getSummaries().get("data/items/0/text"),
                "an array index is a step of the pointer: data/items/0/text");
        assertNull(artifact.getSummaries().get("data/items/1/text"));
        Object items = artifact.getData().get("items");
        assertInstanceOf(List.class, items);
    }

    @Test
    public void jsonPointerKeyEscaping() throws Exception {
        // RFC 6901: '/' -> '~1', '~' -> '~0'.
        ObjectNode raw = NucleoJsonSerializer.createObjectNode();
        raw.put("a/b", repeat("z", 1500));
        raw.put("c~d", repeat("w", 1500));

        MCPArtifact artifact = MCPResultWalker.walk(raw, HANDLE, "tool_a");

        NucleoJsonSerializer.writeSummarized(artifact);
        assertNotNull(artifact.getSummaries().get("data/a~1b"),
                "'/' in key should be escaped to '~1' in JSON pointer");
        assertNotNull(artifact.getSummaries().get("data/c~0d"),
                "'~' in key should be escaped to '~0' in JSON pointer");
    }

    @Test
    public void nullInputProducesArtifactWithNullData() {
        MCPArtifact artifact = MCPResultWalker.walk(null, HANDLE, "tool_a");
        assertNotNull(artifact);
        assertNull(artifact.getData(), "null input should leave data null, not auto-init to empty map");
        assertEquals("test", artifact.getMcpHandle());
        assertEquals("tool_a", artifact.getToolName());
    }

    @Test
    public void nonObjectTopLevelWrappedUnderValueKey() {
        JsonNode primitive = NucleoJsonSerializer.valueToTree("just a string");
        MCPArtifact artifact = MCPResultWalker.walk(primitive, HANDLE, "tool_a");

        assertEquals("just a string", artifact.getData().get("value"),
                "non-object top-level should be wrapped under 'value' key");
    }
}
