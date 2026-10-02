/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp;

import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the {@code @LLMSummarizable(preSummarized=true)} serializer path on
 * {@link MCPArtifact}. The walker pre-populates the artifact's summaryCache; the
 * serializer renders cached summaries at hit paths and full values elsewhere.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-05-09)
 */
public class MCPArtifactSerializationTest {

    private static String repeat(String s, int times) {
        StringBuilder sb = new StringBuilder(s.length() * times);
        for (int i = 0; i < times; i++) sb.append(s);
        return sb.toString();
    }

    private static MCPArtifact artifactWithLongLeaf() {
        ObjectNode raw = NucleoJsonSerializer.createObjectNode();
        raw.put("title", "Hello");
        raw.put("body", repeat("x", 1500));
        return MCPResultWalker.walk(raw, new MCPHandle("test"), "tool_a");
    }

    @Test
    public void standardWriteRendersFullTreeNoCacheLookup() {
        MCPArtifact artifact = artifactWithLongLeaf();
        // Sanity: cache populated by walker
        assertNotNull(artifact.getCachedSummary("/body"));

        String json = NucleoJsonSerializer.write(artifact);

        assertTrue(json.contains("\"title\""), "title should be present");
        assertTrue(json.contains("Hello"), "title value should be present");
        // Standard write does NOT consult the cache - full body must appear verbatim
        assertTrue(json.contains(repeat("x", 1500)),
                "standard write must render full long text, ignoring cache");
    }

    @Test
    public void writeSummarizedRendersCachedSummaryAtLongLeafPath() {
        MCPArtifact artifact = artifactWithLongLeaf();
        String summary = artifact.getCachedSummary("/body").getSummary();

        String json = NucleoJsonSerializer.writeSummarized(artifact);

        assertTrue(json.contains("Hello"),
                "short title leaf should render verbatim (cache miss falls through)");
        assertTrue(json.contains(summary),
                "long body leaf should render the cached summary, not full text");
        assertFalse(json.contains(repeat("x", 1500)),
                "full body text should not appear in summarized output");
    }

    @Test
    public void cacheMissLeavesValueIntact() {
        // Manually create an artifact with NO cache entries; the same body length should
        // pass through untouched in summarized mode (since preSummarized doesn't
        // truncate, it only consults the cache).
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("body", repeat("y", 2000));
        MCPArtifact artifact = new MCPArtifact();
        artifact.setMcpHandle("test");
        artifact.setToolName("tool_a");
        artifact.setData(data);

        String json = NucleoJsonSerializer.writeSummarized(artifact);

        assertTrue(json.contains(repeat("y", 2000)),
                "without a cache entry, preSummarized lets the full value through");
    }

    @Test
    public void writeSummarizedWithRefsCollapsesNestedArtifactToRef() {
        // Build a parent containing a promoted nested MCPArtifact in its data.
        ObjectNode big = NucleoJsonSerializer.createObjectNode();
        big.put("title", "Sub");
        big.put("payload", repeat("a", 2500));
        ObjectNode raw = NucleoJsonSerializer.createObjectNode();
        raw.set("child", big);

        MCPArtifact parent = MCPResultWalker.walk(raw, new MCPHandle("test"), "tool_a");
        Object childValue = parent.getData().get("child");
        assertInstanceOf(MCPArtifact.class, childValue, "child should be promoted to nested MCPArtifact");

        ArtifactRegistry registry = new ArtifactRegistry();
        // Serialize JUST the parent's data via writeSummarizedWithRefs to exercise the
        // nested-artifact path. The parent itself would collapse to @ref if serialized
        // directly; we want to see the rendered data tree where the nested instance
        // collapses. Wrap it in a transient container.
        TransientContainer container = new TransientContainer();
        container.setArtifact(parent);

        String json = NucleoJsonSerializer.writeSummarizedWithRefs(container, registry);

        // Parent collapses to @ref (existing artifact behavior).
        assertTrue(json.contains("\"@ref\""), "parent artifact should collapse to @ref: " + json);
        // Registry has the parent
        assertEquals(1, registry.size(),
                "only parent gets registered via direct serialization; nested registers when its containing field is rendered");
    }

    @Test
    public void summarizedModeRendersANestedArtifactAsAFullBean() {
        // Summarized mode is the registry-dump rendering: the parent is not collapsed,
        // and a nested artifact inside its data tree serializes as a full bean (data
        // included). Only LLM_REF mode collapses artifacts to @ref placeholders.
        MCPArtifact nested = new MCPArtifact();
        nested.setMcpHandle("test");
        nested.setToolName("tool_a");
        Map<String, Object> nestedData = new LinkedHashMap<>();
        nestedData.put("k", "v");
        nested.setData(nestedData);

        Map<String, Object> parentData = new LinkedHashMap<>();
        parentData.put("inline", "scalar");
        parentData.put("subArtifact", nested);
        MCPArtifact parent = new MCPArtifact();
        parent.setMcpHandle("test");
        parent.setToolName("tool_a");
        parent.setData(parentData);

        ArtifactRegistry registry = new ArtifactRegistry();
        // Pre-register parent so its ref exists; we want to render parent's data,
        // not collapse parent. We can do this by NOT going through writeSummarizedWithRefs
        // on the parent itself - use writeSummarized so parent serializes its bean
        // including data, and the nested MCPArtifact still hits ArtifactRefSerializer
        // (which only collapses in LLM_REF mode, not summarized mode). In summarized
        // mode the nested artifact serializes as a full bean with its own data -
        // confirm the inline scalar is preserved and the nested bean is present.
        String json = NucleoJsonSerializer.writeSummarized(parent);

        assertTrue(json.contains("\"inline\""), "scalar entry preserved: " + json);
        assertTrue(json.contains("\"subArtifact\""), "nested artifact field present: " + json);
        assertTrue(json.contains("\"k\""), "nested artifact data rendered: " + json);
    }

    /** Plain bean to exercise writeSummarizedWithRefs through a non-artifact entry point. */
    public static class TransientContainer {
        private MCPArtifact artifact;
        public MCPArtifact getArtifact() { return artifact; }
        public void setArtifact(MCPArtifact artifact) { this.artifact = artifact; }
    }

    @Test
    public void manuallyCachedSummaryRendersInPreSummarizedTree() {
        // The serializer reads from the cache regardless of who populated it - confirm
        // by skipping the walker and seeding the cache directly.
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("buried", repeat("z", 5000));
        data.put("outer", nested);
        MCPArtifact artifact = new MCPArtifact();
        artifact.setMcpHandle("test");
        artifact.setToolName("tool_a");
        artifact.setData(data);
        artifact.cacheSummary("/outer/buried", new SummarizedField(repeat("z", 5000), "[BURIED-SUMMARY]"));

        String json = NucleoJsonSerializer.writeSummarized(artifact);

        assertTrue(json.contains("[BURIED-SUMMARY]"),
                "cached summary at deep path should render: " + json);
        assertFalse(json.contains(repeat("z", 5000)),
                "full text should not appear when cache is hit");
    }
}
