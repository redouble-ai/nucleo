/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.anthropic;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.llm.*;
import org.slf4j.*;

import java.util.*;

/**
 * Content formatter for Anthropic Claude API.
 * Handles Anthropic-specific formatting requirements:
 * - JSON wrapped in code blocks
 * - Limited valid roles (user, system)
 * - Specific image format support
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-10-23)
 */
public class AnthropicContentFormatter implements ContentFormatter {
    private static final Logger log = LoggerFactory.getLogger(AnthropicContentFormatter.class);
    private static final Set<String> VALID_ROLES = Set.of("user", "system");
    private static final Set<String> SUPPORTED_IMAGES =
        Set.of("image/jpeg", "image/png", "image/gif", "image/webp");

    @Override
    public String formatJson(JsonBlock block) {
        // Anthropic prefers JSON wrapped in code blocks for clarity
        if (block != null && block.json() != null) {
            return "```json\n" + block.json() + "\n```";
        }
        return "";
    }
    @Override
    public boolean supportsNativeToolCalling() {
        return true;
    }

    @Override
    public void validateImage(ImageBlock block) {
        if (block != null && block.mimeType() != null) {
            if (!SUPPORTED_IMAGES.contains(block.mimeType())) {
                throw new IllegalArgumentException(
                    "Anthropic only supports JPEG, PNG, GIF, and WebP images. Received: " + block.mimeType());
            }
        }
    }

    @Override
    public void validateFile(FileBlock block) {
        // Anthropic doesn't directly support file uploads
        // Log a warning but don't throw an exception
        if (block != null) {
            log.warn("Anthropic API doesn't support direct file uploads. File '{}' ({}) will be skipped or handled as text.", block.filename(), block.mimeType());
        }
    }

    @Override
    public String normalizeRole(String role) {
        if (role == null) {
            return "user"; // Default to user
        }

        String lowerRole = role.toLowerCase();

        // Check if it's already valid
        if (VALID_ROLES.contains(lowerRole)) {
            return lowerRole;
        }

        // Map common roles to Anthropic equivalents
        switch (lowerRole) {
            case "tool":
            case "function":
            case "human":
                return "user"; // Tools and humans speak as user in Anthropic
            case "assistant":
            case "ai":
            case "bot":
                // Assistant is valid for responses but we shouldn't set it for outgoing
                throw new IllegalArgumentException(
                    "Cannot set 'assistant' role on outgoing messages. Use 'user' or 'system'.");
            default:
                throw new IllegalArgumentException(
                    "Invalid role for Anthropic: " + role + ". Valid roles are: user, system");
        }
    }

    @Override
    public Set<String> getValidRoles() {
        return VALID_ROLES;
    }

    @Override
    public Set<String> getSupportedImageTypes() {
        return SUPPORTED_IMAGES;
    }

    @Override
    public Set<String> getSupportedFileTypes() {
        // Anthropic doesn't support file uploads directly
        return Collections.emptySet();
    }

    @Override
    public String buildTextContent(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        boolean first = true;

        for (ContentBlock block : blocks) {
            if (block instanceof ToolResultBlock) {
                continue;
            }

            if (!first) {
                sb.append("\n\n");
            }
            first = false;

            switch (block) {
                case TextBlock tb -> sb.append(tb.text());
                case PojoBlock pb -> throw new IllegalStateException(
                        "PojoBlock should have been processed by ContentProcessor before reaching the formatter: " + pb.pojo().getClass().getSimpleName());
                case JsonBlock jb -> sb.append(formatJson(jb));
                case ImageBlock ib -> {
                    if (ib.description() != null) {
                        sb.append("[Image: ").append(ib.description()).append("]");
                    }
                }
                case FileBlock fb -> sb.append("[File: ").append(fb.filename()).append("]");
                case ToolUseBlock tu -> sb.append("[Tool Use: ").append(tu.toolName()).append("]");
                case ToolResultBlock tr -> {}
                case ToolDefinitionBlock td -> {
                    // carried by PreparedConversation.toolDefinitions; rendering here too
                    // would put the palette on the wire twice
                }
                case ThinkingBlock th -> {
                    // Thinking blocks are echoed back verbatim via the structured API path,
                    // not rendered into the text-format representation.
                }
                case RedactedThinkingBlock rt -> {
                    // Same as ThinkingBlock: structured replay only.
                }
                case SkillBlock sb2 -> {
                    // The canonical in-message skill text - the literal lives in Skill, never here
                    sb.append(ai.redouble.nucleo.prompt.skill.Skill.renderBody(sb2.skill()));
                }
            }
        }

        return sb.toString();
    }
}