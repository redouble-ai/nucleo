/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import java.util.*;

/**
 * A conversation flattened for one provider, with the channel distinction preserved:
 * the system content and the turns are separate, named parts. Carrying the distinction
 * in the type is the point - a flat list would force a role convention onto the
 * objective, and a client that needs to know which entry is the system content would
 * have to reach around this type to the raw conversation to find out.
 *
 * <p>{@code systemText} is the main objective, already rendered through the same
 * processing pipeline as everything else (artifacts to refs, pojos serialized, tool
 * definitions per the provider's formatter). It is the conversation's ONLY system
 * content. A provider with a system channel puts it there; a provider without one
 * expresses it however that provider carries instructions. Empty when the conversation
 * has no objective.
 *
 * <p>{@code cacheSystem} is the conversation's {@code cacheMainObjective} flag: whether
 * the system content should be marked cacheable on providers with explicit cache
 * control.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-23)
 */
public record PreparedConversation(
    String systemText,
    boolean cacheSystem,
    List<ContentBlocks.ToolDefinitionBlock> toolDefinitions,
    List<ProcessedMessageData> turns
) {
    public boolean hasSystemText() {
        return systemText != null && !systemText.isEmpty();
    }

    /**
     * Every tool definition this request carries: the initial palette plus anything
     * announced mid-conversation in the turns, by name, later announcements winning.
     * A client with a native tools API sends exactly this list; a text-rendering
     * client renders the palette after the system text and the mid-conversation
     * announcements where they sit.
     */
    public List<ContentBlocks.ToolDefinitionBlock> allToolDefinitions() {
        Map<String, ContentBlocks.ToolDefinitionBlock> byName = new LinkedHashMap<>();
        for (ContentBlocks.ToolDefinitionBlock td : toolDefinitions) {
            byName.put(td.name(), td);
        }
        for (ProcessedMessageData turn : turns) {
            for (ContentBlocks.ContentBlock block : turn.contentBlocks()) {
                if (block instanceof ContentBlocks.ToolDefinitionBlock td) {
                    byName.put(td.name(), td);
                }
            }
        }
        return List.copyOf(byName.values());
    }
}
