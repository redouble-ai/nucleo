/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.llm.*;
import org.slf4j.*;

import java.util.*;

/**
 * Content formatter for OpenAI GPT API.
 * Handles OpenAI-specific formatting requirements:
 * - Raw JSON (no code blocks)
 * - Extended valid roles (user, assistant, system, function, tool)
 * - Flexible image format support
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-10-23)
 */
public class OpenAIContentFormatter implements ContentFormatter {
    private static final Logger log = LoggerFactory.getLogger(OpenAIContentFormatter.class);
    private static final Set<String> VALID_ROLES =
        Set.of("user", "assistant", "system", "function", "tool");
    private static final Set<String> SUPPORTED_IMAGES =
        Set.of("image/jpeg", "image/png", "image/gif", "image/webp");

    /**
     * The dialect has a native tool channel - the {@code tools} parameter out, {@code tool_calls}
     * back - and every client of it wires that channel, so the response schema carries no
     * {@code tool_calls} field and the model calls tools the way the API means it to.
     */
    @Override
    public boolean supportsNativeToolCalling() {
        return true;
    }

    @Override
    public String formatJson(JsonBlock block) {
        // OpenAI uses raw JSON without code blocks
        if (block != null && block.json() != null) {
            return block.json();
        }
        return "";
    }
    @Override
    public void validateImage(ImageBlock block) {
        // OpenAI is more flexible with image formats
        // Just log a warning for unsupported types
        if (block != null && block.mimeType() != null) {
            if (!SUPPORTED_IMAGES.contains(block.mimeType())) {
                log.warn("OpenAI may not fully support image type: {}. Recommended types: JPEG, PNG, GIF, WebP", block.mimeType());
            }
        }
    }

    @Override
    public void validateFile(FileBlock block) {
        // OpenAI treats some files as images if they're image-compatible
        if (block != null && block.mimeType() != null) {
            if (isImageCompatible(block.mimeType())) {
                // Can be handled as an image
                log.debug("File '{}' can be sent as an image to OpenAI", block.filename());
            } else {
                log.warn("OpenAI doesn't directly support file type: {} for file '{}'. Will be noted in text.", block.mimeType(), block.filename());
            }
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

        // Map common aliases
        switch (lowerRole) {
            case "human":
                return "user";
            case "ai":
            case "bot":
                return "assistant";
            default:
                throw new IllegalArgumentException(
                    "Invalid role for OpenAI: " + role + ". Valid roles are: " + VALID_ROLES);
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
        // OpenAI can handle image files
        // For other file types, they're typically included as text references
        return Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp",
            "application/pdf" // PDFs can sometimes be handled specially
        );
    }

    /**
     * Checks if a MIME type represents an image that OpenAI can process.
     *
     * @param mimeType the MIME type to check
     * @return true if it's an image type
     */
    private boolean isImageCompatible(String mimeType) {
        return mimeType != null && mimeType.startsWith("image/");
    }
}