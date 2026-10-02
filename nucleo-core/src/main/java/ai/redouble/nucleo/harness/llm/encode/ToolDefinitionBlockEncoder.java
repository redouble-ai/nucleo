/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm.encode;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;


/**
 * Default {@link ToolDefinitionBlock} encoder: a readable text rendering of the tool's
 * name, description and parameter schema. Providers with a native tools API subclass to
 * return null and collect the blocks into their tools parameter instead - the override
 * and the collection live in the same client, so neither half can drift alone.
 *
 * <p>{@link #renderText} is the single authority for the text form. Every token count of
 * a tool definition measures this exact string, so what the estimate counts and what the
 * text-fallback wire carries cannot diverge.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-18)
 */
public class ToolDefinitionBlockEncoder<B> extends BlockEncoder<B> {

    /** The one text form of a tool definition: wire rendering and token counting both use it. */
    public static String renderText(ToolDefinitionBlock block) {
        StringBuilder sb = new StringBuilder();
        sb.append(block.name()).append(": ").append(block.description());
        if (block.schemaJson() != null && !block.schemaJson().isEmpty()) {
            sb.append("\n  Input parameters: ").append(block.schemaJson());
        }
        return sb.toString();
    }

    @Override
    public B encode(ContentBlock block) {
        return textWrapper.wrap(renderText((ToolDefinitionBlock) block));
    }
}
