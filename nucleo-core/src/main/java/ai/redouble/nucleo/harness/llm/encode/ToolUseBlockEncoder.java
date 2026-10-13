/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm.encode;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;


/**
 * Default {@link ToolUseBlock} encoder: a readable text rendering of the tool call. Providers with a
 * native tool-use representation (Anthropic) subclass and override.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-18)
 */
public class ToolUseBlockEncoder<B> extends BlockEncoder<B> {
    /** The text form of a tool call: what this encoder sends, and what an estimate counts for it. */
    public static String renderText(ToolUseBlock block) {
        return "[Tool Use " + block.toolName() + "] " + block.inputJson();
    }

    @Override
    public B encode(ContentBlock block) {
        return textWrapper.wrap(renderText((ToolUseBlock) block));
    }
}
