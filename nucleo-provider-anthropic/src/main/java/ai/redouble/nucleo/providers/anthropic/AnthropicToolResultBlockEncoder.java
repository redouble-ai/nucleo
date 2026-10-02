/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.anthropic;

import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.llm.encode.*;
import com.anthropic.models.messages.*;

/**
 * Anthropic native tool-result: a {@code ToolResultBlockParam} linked to its tool-use id, so the
 * model sees a real tool result rather than text.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-18)
 */
public class AnthropicToolResultBlockEncoder extends ToolResultBlockEncoder<ContentBlockParam> {
    @Override
    public ContentBlockParam encode(ContentBlocks.ContentBlock block) {
        ContentBlocks.ToolResultBlock tr = (ContentBlocks.ToolResultBlock) block;
        return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                .toolUseId(tr.toolUseId()).content(tr.resultJson()).isError(tr.isError()).build());
    }
}
