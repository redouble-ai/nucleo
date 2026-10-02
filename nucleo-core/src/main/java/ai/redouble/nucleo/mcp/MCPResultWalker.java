/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp;

import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.*;

import java.lang.annotation.*;
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
 *   <li>String leaf longer than {@link #LONG_TEXT_THRESHOLD} characters - the full
 *       text stays in {@code data}, and the artifact's inherited
 *       {@code summaryCache} gets an entry keyed by JSON pointer
 *       (e.g. {@code /results/0/fullText}) carrying a summary produced by
 *       {@link TruncatingSummarizer}. The serializer renders the summary in
 *       summarized modes; the full text remains available to
 *       {@code get_artifact_field} and search tools.</li>
 *   <li>Anything smaller passes through verbatim.</li>
 * </ul>
 *
 * <p>JSON pointer paths reset to the root inside each promoted nested artifact -
 * each artifact's cache is keyed relative to its own {@code data}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-05-09)
 */
public final class MCPResultWalker {
    /** Strings longer than this get a summaryCache entry; full text stays in data. */
    private static final int LONG_TEXT_THRESHOLD = 1000;
    /** Object values whose serialized form exceeds this become nested MCPArtifacts. */
    private static final int PROMOTE_OBJECT_THRESHOLD = 2000;
    private static final Summarizer SUMMARIZER = new TruncatingSummarizer();
    private static final LLMSummarizable WALKER_HINT = new LLMSummarizable() {
        @Override public Class<? extends Annotation> annotationType() { return LLMSummarizable.class; }
        @Override public String value() { return "MCP tool result text"; }
        @Override public int threshold() { return LONG_TEXT_THRESHOLD; }
        @Override public String staticSummary() { return ""; }
        @Override public boolean llmSafe() { return true; }
        @Override public boolean preSummarized() { return false; }
        @Override public SummarySize size() { return SummarySize.SHORT; }
    };

    private MCPResultWalker() {
    }
    /**
     * Walks the supplied tree and produces an {@link MCPArtifact}. Provenance fields
     * populated; summary cache populated for any long string leaves; nested objects
     * past the promotion threshold appear as nested {@link MCPArtifact} values inside
     * {@code data}. {@code null} or null-node input produces an artifact with null
     * {@code data}.
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
            walkObject(raw, "", data, artifact);
        }
        else {
            data.put("value", walkValue(raw, "/value", artifact));
        }
        artifact.setData(data);
        return artifact;
    }
    private static void walkObject(JsonNode obj, String pathPrefix, Map<String, Object> out, MCPArtifact root) {
        for (Map.Entry<String, JsonNode> e : obj.properties()) {
            String key = e.getKey();
            String path = pathPrefix + "/" + escapeJsonPointer(key);
            out.put(key, walkValue(e.getValue(), path, root));
        }
    }
    private static Object walkValue(JsonNode node, String path, MCPArtifact root) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            if (shouldPromote(node)) {
                MCPArtifact nested = new MCPArtifact();
                nested.setMcpHandle(root.getMcpHandle());
                nested.setToolName(root.getToolName());
                Map<String, Object> nestedData = new LinkedHashMap<>();
                walkObject(node, "", nestedData, nested);
                nested.setData(nestedData);
                return nested;
            }
            Map<String, Object> inline = new LinkedHashMap<>();
            walkObject(node, path, inline, root);
            return inline;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>(node.size());
            for (int i = 0; i < node.size(); i++) {
                list.add(walkValue(node.get(i), path + "/" + i, root));
            }
            return list;
        }
        if (node.isTextual()) {
            String text = node.asText();
            if (text.length() > LONG_TEXT_THRESHOLD) {
                root.cacheSummary(path, new SummarizedField(text, SUMMARIZER.summarize(text, WALKER_HINT)));
            }
            return text;
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
    /** Per RFC 6901: '~' -> '~0', '/' -> '~1'. */
    private static String escapeJsonPointer(String key) {
        if (key.indexOf('~') < 0 && key.indexOf('/') < 0) {
            return key;
        }
        return key.replace("~", "~0").replace("/", "~1");
    }
}
