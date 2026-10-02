/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm.encode;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;


/**
 * Default {@link ToolResultBlock} encoder: a readable text rendering of the tool result. Providers
 * with a native tool-result representation (Anthropic) subclass and override.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-18)
 */
public class ToolResultBlockEncoder<B> extends BlockEncoder<B> {
    @Override
    public B encode(ContentBlock block) {
        ToolResultBlock r = (ToolResultBlock) block;
        return textWrapper.wrap("[Tool Result " + r.toolUseId() + "] " + r.resultJson());
    }
}
