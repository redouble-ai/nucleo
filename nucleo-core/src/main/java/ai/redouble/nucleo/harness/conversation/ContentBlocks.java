/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.prompt.skill.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

import java.util.*;

/**
 * Data classes for storing multimodal content in messages.
 * These blocks store content separately, allowing each LLM client
 * to format them according to their API requirements.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-10-20)
 */
public class ContentBlocks {
    /**
     * Base interface for all content blocks.
     * Sealed to ensure exhaustive pattern matching.
     */
    public sealed interface ContentBlock
        permits TextBlock, PojoBlock, JsonBlock, ToolDefinitionBlock, ImageBlock, FileBlock, ToolUseBlock, ToolResultBlock, ThinkingBlock, RedactedThinkingBlock, SkillBlock {}
    /**
     * Plain text content.
     *
     * @param text The text content
     */
    public record TextBlock(String text) implements ContentBlock {}
    /**
     * Structured data that needs provider-specific formatting.
     * For example, Anthropic wraps JSON in code blocks while OpenAI uses raw JSON.
     *
     * @param pojo The object to be serialized
     */
    public record PojoBlock(Object pojo) implements ContentBlock {}
    /**
     * Raw JSON content that needs provider-specific formatting.
     * This is the base block for JSON data - PojoBlock delegates to this.
     *
     * @param json The raw JSON string
     */
    public record JsonBlock(String json) implements ContentBlock {}
    /**
     * Tool definition with schema for LLM tool calling.
     * Contains the tool name, description, and standard JSON Schema for parameters.
     * A definition is its own {@link DeclaredTool}, so pre-built and restored
     * definitions declare into a conversation directly.
     *
     * @param name The tool name
     * @param description The tool description
     * @param schemaJson standard JSON Schema (type/properties/required) for tool parameters
     */
    public record ToolDefinitionBlock(String name, String description, String schemaJson) implements ContentBlock, DeclaredTool {
        @Override
        public ToolDefinitionBlock definition() {
            return this;
        }
    }
    /**
     * Represents an image to be sent to the LLM.
     * The image data is stored as base64-encoded string.
     *
     * @param base64 The base64-encoded image data
     * @param mimeType The MIME type of the image (e.g., "image/png", "image/jpeg")
     * @param description Optional description of the image for context
     */
    public record ImageBlock(String base64, String mimeType, String description) implements ContentBlock {}
    /**
     * Represents a file (like PDF) to be sent to the LLM.
     * The file data is stored as base64-encoded string.
     *
     * @param base64 The base64-encoded file data
     * @param mimeType The MIME type of the file (e.g., "application/pdf")
     * @param filename The original filename
     */
    public record FileBlock(String base64, String mimeType, String filename) implements ContentBlock {}

    /**
     * Represents a tool invocation from the LLM.
     * When the LLM decides to call a tool, it provides the tool name,
     * a unique invocation ID, and the input parameters as JSON.
     *
     * @param toolUseId Unique identifier for this tool invocation (from provider)
     * @param toolName Name of the tool to invoke
     * @param inputJson Tool input parameters as JSON string
     */
    public record ToolUseBlock(String toolUseId, String toolName, String inputJson) implements ContentBlock {}

    /**
     * Represents the result of a tool execution being sent back to the LLM.
     * Links to the original tool invocation via toolUseId.
     *
     * @param toolUseId Reference to the ToolUseBlock that requested this
     * @param resultJson Tool output as JSON string
     * @param isError Whether this result represents an error
     */
    public record ToolResultBlock(String toolUseId, String resultJson, boolean isError) implements ContentBlock {}

    /**
     * Anthropic extended / adaptive thinking block. {@code text} carries the model's
     * readable reasoning (populated only when the request asked for it: EXTENDED models
     * return text by default; ADAPTIVE models require {@code display: "summarized"}).
     * {@code signature} is an opaque token that Anthropic requires be echoed back verbatim
     * when the block appears in a later turn's assistant history, or the request will 400.
     *
     * @param text readable thinking text, may be empty string but never null
     * @param signature opaque round-trip token
     */
    public record ThinkingBlock(String text, String signature) implements ContentBlock {}

    /**
     * Anthropic-redacted thinking block. The model produced reasoning that policy hid from
     * the caller; {@code data} is the opaque payload we must replay verbatim on later turns.
     * There is no readable text.
     *
     * @param data opaque round-trip payload
     */
    public record RedactedThinkingBlock(String data) implements ContentBlock {}

    /**
     * Admitted Skill bundle carried in the conversation preamble. Per-provider rendering
     * applies: Anthropic emits the body as a cacheable system-block; OpenAI prepends a
     * {@code role:system} message; Bedrock adds a SystemContentBlock. The block's primary
     * residence is {@code ConversationContext.loadedSkills} rather than the outgoing-message
     * content list; the sealed permit lets persistence/snapshot paths round-trip the same
     * shape.
     *
     * @param skill the admitted skill bundle
     */
    public record SkillBlock(Skill skill) implements ContentBlock {}

    /**
     * Serializes a content block to a JSON node with a type discriminator.
     * JSON string fields (JsonBlock.json, ToolUseBlock.inputJson, etc.) are parsed
     * into the tree so the entire document is navigable without double-escaping.
     *
     * @param block the content block to serialize
     * @return an ObjectNode representing the block
     */
    public static ObjectNode toJsonNode(ContentBlock block) {
        ObjectNode node = NucleoJsonSerializer.createObjectNode();
        switch (block) {
            case TextBlock tb -> {
                node.put("type", "text");
                node.put("text", tb.text());
            }
            case PojoBlock pb -> {
                node.put("type", "pojo");
                node.set("json", parseJsonString(NucleoJsonSerializer.writeSummarized(pb.pojo())));
            }
            case JsonBlock jb -> {
                node.put("type", "json");
                node.set("json", parseJsonString(jb.json()));
            }
            case ToolDefinitionBlock td -> {
                node.put("type", "tool_definition");
                node.put("name", td.name());
                node.put("description", td.description());
                if (td.schemaJson() != null) {
                    node.set("schema", parseJsonString(td.schemaJson()));
                }
            }
            case ImageBlock ib -> {
                node.put("type", "image");
                node.put("mimeType", ib.mimeType());
                if (ib.description() != null) {
                    node.put("description", ib.description());
                }
                node.put("base64", ib.base64());
            }
            case FileBlock fb -> {
                node.put("type", "file");
                node.put("mimeType", fb.mimeType());
                node.put("filename", fb.filename());
                node.put("base64", fb.base64());
            }
            case ToolUseBlock tu -> {
                node.put("type", "tool_use");
                node.put("toolUseId", tu.toolUseId());
                node.put("toolName", tu.toolName());
                node.set("input", parseJsonString(tu.inputJson()));
            }
            case ToolResultBlock tr -> {
                node.put("type", "tool_result");
                node.put("toolUseId", tr.toolUseId());
                node.put("isError", tr.isError());
                node.set("result", parseJsonString(tr.resultJson()));
            }
            case ThinkingBlock th -> {
                node.put("type", "thinking");
                node.put("text", th.text());
                node.put("signature", th.signature());
            }
            case RedactedThinkingBlock rt -> {
                node.put("type", "redacted_thinking");
                node.put("data", rt.data());
            }
            case SkillBlock sb -> {
                node.put("type", "skill");
                node.put("name", sb.skill().name());
                node.set("skill", parseJsonString(NucleoJsonSerializer.writeSummarized(sb.skill())));
            }
        }
        return node;
    }

    /**
     * Serializes a list of content blocks to a JSON array string.
     *
     * @param blocks the content blocks to serialize
     * @return JSON array string
     */
    public static String toJson(List<ContentBlock> blocks) {
        ArrayNode array = NucleoJsonSerializer.createArrayNode();
        for (ContentBlock block : blocks) {
            array.add(toJsonNode(block));
        }
        return array.toString();
    }

    /**
     * Parses a JSON string into a JsonNode for embedding in the tree.
     * If the string is not valid JSON, wraps it as a text value node.
     */
    private static JsonNode parseJsonString(String json) {
        if (json == null) {
            return NullNode.getInstance();
        }
        try {
            return NucleoJsonSerializer.readTree(json);
        }
        catch (Exception e) {
            // Not valid JSON - embed as raw text
            return TextNode.valueOf(json);
        }
    }
}