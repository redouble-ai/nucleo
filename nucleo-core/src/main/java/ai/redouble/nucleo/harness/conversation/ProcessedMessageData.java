/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;

import java.util.*;

/**
 * Immutable data structure containing one conversation turn ready for LLM consumption.
 *
 * <p>This record represents a turn after all processing has been applied:
 * <ul>
 *   <li>Artifacts have been replaced with @ref placeholders</li>
 *   <li>Content has been formatted appropriately</li>
 *   <li>Response instructions have been appended</li>
 * </ul>
 *
 * <p>LLM clients should use this data to build provider-specific requests
 * without needing to understand artifact processing or content transformation.
 * The role is a {@link TurnRole}, never a string: system content is not a turn
 * and travels separately on {@link PreparedConversation}.
 *
 * @param role The turn role
 * @param contentBlocks All content blocks in order (text, images, tools, etc.)
 * @param cacheEnabled Whether this turn should use cache control (Anthropic)
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-14)
 */
public record ProcessedMessageData(
    TurnRole role,
    List<ContentBlock> contentBlocks,
    boolean cacheEnabled
) {
    /**
     * Creates a text-only turn with no multimodal content.
     *
     * @param role The turn role
     * @param textContent The processed text content
     * @param cacheEnabled Whether to enable caching
     * @return A new ProcessedMessageData instance
     */
    public static ProcessedMessageData textOnly(TurnRole role, String textContent, boolean cacheEnabled) {
        List<ContentBlock> blocks = (textContent != null && !textContent.isEmpty())
            ? List.of(new TextBlock(textContent))
            : Collections.emptyList();
        return new ProcessedMessageData(role, blocks, cacheEnabled);
    }
    /**
     * Checks if this turn has multimodal content (non-text blocks).
     *
     * @return true if the turn contains non-text blocks
     */
    public boolean hasMultimodalContent() {
        if (contentBlocks == null || contentBlocks.isEmpty()) {
            return false;
        }
        return contentBlocks.stream().anyMatch(block -> !(block instanceof TextBlock));
    }
}
