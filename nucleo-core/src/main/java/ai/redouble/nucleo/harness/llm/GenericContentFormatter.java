/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;

import java.util.*;

/**
 * Generic content formatter for providers without specific requirements.
 * Provides sensible defaults that work with most LLM APIs:
 * - Raw JSON (no special formatting)
 * - Permissive role validation
 * - Accepts common image/file formats
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-10-23)
 */
public class GenericContentFormatter implements ContentFormatter {
    private static final Set<String> VALID_ROLES =
        Set.of("user", "assistant", "system", "function", "tool", "human", "ai", "bot");
    private static final Set<String> SUPPORTED_IMAGES =
        Set.of("image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp", "image/tiff");

    @Override
    public String formatJson(JsonBlock block) {
        // Generic - just raw JSON
        if (block != null && block.json() != null) {
            return block.json();
        }
        return "";
    }
    @Override
    public void validateImage(ImageBlock block) {
        // Generic formatter is permissive - accept all common image types
        // No validation, just accept whatever is provided
    }

    @Override
    public void validateFile(FileBlock block) {
        // Generic formatter is permissive - accept all files
        // The client can decide how to handle them
    }

    @Override
    public String normalizeRole(String role) {
        if (role == null) {
            return "user"; // Default to user
        }

        String lowerRole = role.toLowerCase();

        // Normalize common aliases
        switch (lowerRole) {
            case "human":
                return "user";
            case "ai":
            case "bot":
                return "assistant";
            default:
                return lowerRole; // Just return lowercase version
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
        // Generic formatter accepts common file types
        return Set.of(
            "text/plain",
            "text/html",
            "text/markdown",
            "application/pdf",
            "application/json",
            "application/xml"
        );
    }
}