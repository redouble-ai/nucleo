/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp;

import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.*;

import java.util.*;

/**
 * Pure transformation: a {@link JsonNode} (an MCP tool's structured response, however
 * we obtained it) into an {@link MCPArtifact} tree. No registry, no I/O.
 *
 * <p>Walk rules:
 * <ul>
 *   <li>Object value whose serialized form exceeds {@link #PROMOTE_OBJECT_THRESHOLD}
 *       bytes - promote to its own nested {@link MCPArtifact}, embedded as the value
 *       at that key in the parent's {@code data}. The framework's existing
 *       artifact-ref serializer collapses each nested instance to {@code {"@ref": ...}}
 *       in LLM-facing serialization, so the LLM sees structure without seeing the
 *       expanded payload of every sub-object.</li>
 *   <li>Anything else passes through verbatim, a long text included.</li>
 * </ul>
 *
 * <p>The walker summarizes nothing. A long text leaf stays whole in {@code data}, and the
 * serializer shows a model its summary, decided by the annotation on
 * {@link MCPArtifact}'s {@code data} as every field's summary is.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-05-09)
 */
public final class MCPResultWalker {
    /** Object values whose serialized form exceeds this become nested MCPArtifacts. */
    private static final int PROMOTE_OBJECT_THRESHOLD = 2000;

    private MCPResultWalker() {
    }

    /**
     * Walks the supplied tree and produces an {@link MCPArtifact}. Provenance fields
     * populated; nested objects past the promotion threshold appear as nested
     * {@link MCPArtifact} values inside {@code data}. {@code null} or null-node input
     * produces an artifact with null {@code data}.
     */
    public static MCPArtifact walk(JsonNode raw, MCPHandle handle, String toolName) {
        MCPArtifact artifact = new MCPArtifact();
        artifact.setMcpHandle(handle.value());
        artifact.setToolName(toolName);
        if (raw == null || raw.isNull()) {
            return artifact;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (raw.isObject()) {
            walkObject(raw, data, artifact);
        }
        else {
            data.put("value", walkValue(raw, artifact));
        }
        artifact.setData(data);
        return artifact;
    }

    private static void walkObject(JsonNode obj, Map<String, Object> out, MCPArtifact root) {
        for (Map.Entry<String, JsonNode> e : obj.properties()) {
            out.put(e.getKey(), walkValue(e.getValue(), root));
        }
    }

    private static Object walkValue(JsonNode node, MCPArtifact root) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            if (shouldPromote(node)) {
                MCPArtifact nested = new MCPArtifact();
                nested.setMcpHandle(root.getMcpHandle());
                nested.setToolName(root.getToolName());
                Map<String, Object> nestedData = new LinkedHashMap<>();
                walkObject(node, nestedData, nested);
                nested.setData(nestedData);
                return nested;
            }
            Map<String, Object> inline = new LinkedHashMap<>();
            walkObject(node, inline, root);
            return inline;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>(node.size());
            for (int i = 0; i < node.size(); i++) {
                list.add(walkValue(node.get(i), root));
            }
            return list;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.toString();
    }

    private static boolean shouldPromote(JsonNode obj) {
        return NucleoJsonSerializer.write(obj).length() > PROMOTE_OBJECT_THRESHOLD;
    }
}
