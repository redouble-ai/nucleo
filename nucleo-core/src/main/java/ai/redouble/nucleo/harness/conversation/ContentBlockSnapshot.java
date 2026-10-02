/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.prompt.skill.*;

/**
 * Snapshot of a ContentBlock for persistence.
 * Preserves the type and data of each block type.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-15)
 */
public class ContentBlockSnapshot  {
    private String type;
    private String text;
    private String json;
    private String toolName;
    private String toolDescription;
    private String toolSchemaJson;
    private String base64;
    private String mimeType;
    private String description;
    private String filename;
    // Pojo data (will be serialized as JSON)
    private String pojoJson;
    private String pojoClass;
    // Tool calling data
    private String toolUseId;
    private String toolInputJson;
    private String toolResultJson;
    private Boolean isError;
    // Thinking-block data. `text` is reused for ThinkingBlock.text; `signature` and `data` are
    // the opaque round-trip payloads Anthropic requires when replaying the block on later turns.
    private String signature;
    private String data;
    // Skill-block data. Restore looks the skill up by name from SkillRegistry.
    private String skillName;
    public ContentBlockSnapshot() {}
    public static ContentBlockSnapshot fromBlock(ContentBlock block) {
        ContentBlockSnapshot snapshot = new ContentBlockSnapshot();
        switch (block) {
            case TextBlock tb -> {
                snapshot.type = "text";
                snapshot.text = tb.text();
            }
            case PojoBlock pb -> {
                snapshot.type = "pojo";
                snapshot.pojoJson = NucleoJsonSerializer.writeSummarized(pb.pojo());
                snapshot.pojoClass = pb.pojo().getClass().getName();
            }
            case JsonBlock jb -> {
                snapshot.type = "json";
                snapshot.json = jb.json();
            }
            case ToolDefinitionBlock td -> {
                snapshot.type = "tool";
                snapshot.toolName = td.name();
                snapshot.toolDescription = td.description();
                snapshot.toolSchemaJson = td.schemaJson();
            }
            case ImageBlock ib -> {
                snapshot.type = "image";
                snapshot.base64 = ib.base64();
                snapshot.mimeType = ib.mimeType();
                snapshot.description = ib.description();
            }
            case FileBlock fb -> {
                snapshot.type = "file";
                snapshot.base64 = fb.base64();
                snapshot.mimeType = fb.mimeType();
                snapshot.filename = fb.filename();
            }
            case ToolUseBlock tu -> {
                snapshot.type = "tool_use";
                snapshot.toolUseId = tu.toolUseId();
                snapshot.toolName = tu.toolName();
                snapshot.toolInputJson = tu.inputJson();
            }
            case ToolResultBlock tr -> {
                snapshot.type = "tool_result";
                snapshot.toolUseId = tr.toolUseId();
                snapshot.toolResultJson = tr.resultJson();
                snapshot.isError = tr.isError();
            }
            case ThinkingBlock th -> {
                snapshot.type = "thinking";
                snapshot.text = th.text();
                snapshot.signature = th.signature();
            }
            case RedactedThinkingBlock rt -> {
                snapshot.type = "redacted_thinking";
                snapshot.data = rt.data();
            }
            case SkillBlock sb -> {
                snapshot.type = "skill";
                snapshot.skillName = sb.skill().name();
            }
        }
        return snapshot;
    }
    public ContentBlock toBlock() {
        return switch (type) {
            case "text" -> new TextBlock(text);
            case "pojo" -> {
                if (pojoClass != null && pojoJson != null) {
                    try {
                        // Dynamically load the class and deserialize
                        Class<?> clazz = Class.forName(pojoClass);
                        Object pojo = NucleoJsonSerializer.parseLLMResponse(pojoJson, clazz);
                        yield new PojoBlock(pojo);
                    } catch (Exception e) {
                        // Fall back to JSON block if class not found or parsing fails
                        yield new JsonBlock(pojoJson);
                    }
                } else {
                    yield new JsonBlock(pojoJson);
                }
            }
            case "json" -> new JsonBlock(json);
            case "tool" -> new ToolDefinitionBlock(toolName, toolDescription, toolSchemaJson);
            case "image" -> new ImageBlock(base64, mimeType, description);
            case "file" -> new FileBlock(base64, mimeType, filename);
            case "tool_use" -> new ToolUseBlock(toolUseId, toolName, toolInputJson);
            case "tool_result" -> new ToolResultBlock(toolUseId, toolResultJson, isError != null && isError);
            case "thinking" -> new ThinkingBlock(text, signature);
            case "redacted_thinking" -> new RedactedThinkingBlock(data);
            case "skill" -> {
                Skill s = SkillRegistry.lookup(skillName);
                if (s == null) {
                    throw new IllegalStateException("Cannot restore SkillBlock: skill '" +
                        skillName + "' is not registered in SkillRegistry");
                }
                yield new SkillBlock(s);
            }
            default -> throw new IllegalStateException("Unknown block type: " + type);
        };
    }

    public String getSkillName() {
        return skillName;
    }

    public void setSkillName(String skillName) {
        this.skillName = skillName;
    }
    // Getters and setters
    public String getType() {
        return type;
    }
    public void setType(String type) {
        this.type = type;
    }
    public String getText() {
        return text;
    }
    public void setText(String text) {
        this.text = text;
    }
    public String getJson() {
        return json;
    }
    public void setJson(String json) {
        this.json = json;
    }
    public String getToolName() {
        return toolName;
    }
    public void setToolName(String toolName) {
        this.toolName = toolName;
    }
    public String getToolDescription() {
        return toolDescription;
    }
    public void setToolDescription(String toolDescription) {
        this.toolDescription = toolDescription;
    }
    public String getToolSchemaJson() {
        return toolSchemaJson;
    }
    public void setToolSchemaJson(String toolSchemaJson) {
        this.toolSchemaJson = toolSchemaJson;
    }
    public String getBase64() {
        return base64;
    }
    public void setBase64(String base64) {
        this.base64 = base64;
    }
    public String getMimeType() {
        return mimeType;
    }
    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }
    public String getDescription() {
        return description;
    }
    public void setDescription(String description) {
        this.description = description;
    }
    public String getFilename() {
        return filename;
    }
    public void setFilename(String filename) {
        this.filename = filename;
    }
    public String getPojoJson() {
        return pojoJson;
    }
    public void setPojoJson(String pojoJson) {
        this.pojoJson = pojoJson;
    }
    public String getPojoClass() {
        return pojoClass;
    }
    public void setPojoClass(String pojoClass) {
        this.pojoClass = pojoClass;
    }
    public String getToolUseId() {
        return toolUseId;
    }
    public void setToolUseId(String toolUseId) {
        this.toolUseId = toolUseId;
    }
    public String getToolInputJson() {
        return toolInputJson;
    }
    public void setToolInputJson(String toolInputJson) {
        this.toolInputJson = toolInputJson;
    }
    public String getToolResultJson() {
        return toolResultJson;
    }
    public void setToolResultJson(String toolResultJson) {
        this.toolResultJson = toolResultJson;
    }
    public Boolean getIsError() {
        return isError;
    }
    public void setIsError(Boolean isError) {
        this.isError = isError;
    }
    public String getSignature() {
        return signature;
    }
    public void setSignature(String signature) {
        this.signature = signature;
    }
    public String getData() {
        return data;
    }
    public void setData(String data) {
        this.data = data;
    }
}