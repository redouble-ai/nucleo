/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp;

import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.schema.*;

import java.util.*;

/**
 * Generic artifact wrapping an MCP tool's structured response. Behaves like every other
 * artifact: registered in the {@code ArtifactRegistry} on serialization, replaced by its
 * ref in LLM-facing output, fetchable by ref via the existing artifact tools.
 *
 * <p>The walker that produces this artifact is responsible for two things:
 * <ol>
 *   <li>Recursively wrapping non-trivial sub-objects in their own {@link MCPArtifact}
 *       instances and embedding them as values in {@link #data}. The framework's
 *       artifact-ref serializer collapses each nested instance to its own ref in
 *       LLM-facing serialization.</li>
 *   <li>Pre-populating the inherited {@code summaryCache} (via
 *       {@link AbstractArtifact#cacheSummary(String, ai.redouble.nucleo.harness.schema.SummarizedField)})
 *       for long string and binary leaves, keyed by JSON pointer into {@link #data}
 *       (e.g. {@code /results/0/fullText}).</li>
 * </ol>
 *
 * <p>The {@code data} field carries {@code @LLMSummarizable(preSummarized=true)} so the
 * framework's serializer walks the tree and renders the cached summary at any leaf where
 * an entry exists, raw value otherwise. No MCP-specific serialization code path needed.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-05-09)
 */
@TypeAlias("mcp")
public class MCPArtifact extends AbstractArtifact {
    @LLMSummarizable(value = "MCP tool result data", preSummarized = true)
    private Map<String, Object> data;
    private String mcpHandle;
    private String toolName;
    public MCPArtifact() {
    }
    public Map<String, Object> getData() {
        return data;
    }
    public void setData(Map<String, Object> data) {
        this.data = data;
    }
    public String getMcpHandle() {
        return mcpHandle;
    }
    public void setMcpHandle(String mcpHandle) {
        this.mcpHandle = mcpHandle;
    }
    public String getToolName() {
        return toolName;
    }
    public void setToolName(String toolName) {
        this.toolName = toolName;
    }
}
