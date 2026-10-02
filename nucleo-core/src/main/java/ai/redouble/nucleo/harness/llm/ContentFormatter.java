/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;

import java.util.*;

/**
 * Interface for provider-specific content formatting.
 * Each LLM provider (Anthropic, OpenAI, etc.) has different requirements for
 * how content should be formatted. This interface encapsulates those differences.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-10-23)
 */
public interface ContentFormatter {
    /**
     * Formats JSON content for this provider.
     * For example, Anthropic prefers JSON wrapped in code blocks,
     * while OpenAI uses raw JSON.
     *
     * @param block the JSON block to format
     * @return formatted string representation
     */
    String formatJson(JsonBlock block);
    /**
     * Formats a text block for this provider.
     * Most providers just return the raw text, but this allows customization.
     *
     * @param block the text block to format
     * @return formatted text
     */
    default String formatText(TextBlock block) {
        return block.text();
    }
    /**
     * Validates an image block for this provider.
     * Different providers support different image formats.
     *
     * @param block the image block to validate
     * @throws IllegalArgumentException if the image format is not supported
     */
    void validateImage(ImageBlock block);
    /**
     * Validates a file block for this provider.
     * Some providers don't support file uploads at all.
     *
     * @param block the file block to validate
     * @throws IllegalArgumentException if the file type is not supported
     */
    void validateFile(FileBlock block);
    /**
     * Normalizes a role string for this provider.
     * Different providers have different valid roles and mappings.
     *
     * @param role the role to normalize (e.g., "user", "assistant", "tool")
     * @return normalized role string
     * @throws IllegalArgumentException if the role is invalid
     */
    String normalizeRole(String role);
    /**
     * Gets the set of valid roles for this provider.
     *
     * @return set of valid role strings
     */
    Set<String> getValidRoles();
    /**
     * Gets the set of supported image MIME types for this provider.
     *
     * @return set of MIME type strings
     */
    Set<String> getSupportedImageTypes();
    /**
     * Gets the set of supported file MIME types for this provider.
     *
     * @return set of MIME type strings
     */
    default Set<String> getSupportedFileTypes() {
        return Collections.emptySet(); // Most providers don't support files
    }
    /**
     * Whether this provider supports native tool calling (tool_use blocks, function calling, etc.).
     * When true, tool definitions are sent via the provider's native mechanism and the response
     * schema excludes tool_calls (the model uses native tool_use blocks instead).
     * When false, tools are described as text in the prompt and tool_calls are part of the JSON response.
     *
     * @return true if native tool calling is supported
     */
    default boolean supportsNativeToolCalling() {
        return false;
    }

    /**
     * Builds complete text content from a list of blocks, applying provider-specific
     * formatting. PojoBlocks must be processed by {@code ContentProcessor} before reaching
     * this method - any PojoBlock here is treated as a programming error.
     *
     * @param blocks the content blocks to format
     * @return formatted text content
     */
    default String buildTextContent(List<ContentBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock || block instanceof JsonBlock) {
                if (!first) {
                    sb.append("\n\n");
                }
                first = false;
                switch (block) {
                    case TextBlock tb -> sb.append(formatText(tb));
                    case JsonBlock jb -> sb.append(formatJson(jb));
                    default -> { /* Skip image and file blocks */ }
                }
            }
            else if (block instanceof PojoBlock pb) {
                throw new IllegalStateException(
                        "PojoBlock should have been processed by ContentProcessor before reaching the formatter: " + pb.pojo().getClass().getSimpleName());
            }
        }
        return sb.toString();
    }
}