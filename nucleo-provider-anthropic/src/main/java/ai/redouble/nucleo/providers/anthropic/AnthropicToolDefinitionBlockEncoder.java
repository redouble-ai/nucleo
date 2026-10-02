/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.anthropic;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.conversation.ContentBlocks.ContentBlock;
import ai.redouble.nucleo.harness.llm.encode.*;
import com.anthropic.models.messages.*;

/**
 * Anthropic {@link ToolDefinitionBlock} encoder: nothing inline. Tool definitions travel
 * through the native {@code tools} request parameter, which
 * {@code AnthropicSDKClient.buildMessageCreateParams} populates by sweeping the prepared
 * turns for these blocks. Both halves of that decision - suppress here, collect there -
 * belong to the one client, registered side by side in its encoder map.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-24)
 */
public class AnthropicToolDefinitionBlockEncoder extends ToolDefinitionBlockEncoder<ContentBlockParam> {
    @Override
    public ContentBlockParam encode(ContentBlock block) {
        return null;
    }
}
