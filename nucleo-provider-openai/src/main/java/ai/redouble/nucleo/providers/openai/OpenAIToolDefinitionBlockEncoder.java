/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.llm.encode.*;
import com.fasterxml.jackson.databind.node.*;

/**
 * Chat Completions {@link ToolDefinitionBlock} encoder: nothing inline. Tool definitions
 * travel through the native {@code tools} request parameter, which
 * {@code AbstractOpenAIChatClient.buildRequestJson} populates by sweeping the prepared
 * conversation for these blocks. Both halves of that decision - suppress here, collect
 * there - belong to the one client, registered side by side in its encoder map.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
public class OpenAIToolDefinitionBlockEncoder extends ToolDefinitionBlockEncoder<ObjectNode> {
    @Override
    public ObjectNode encode(ContentBlock block) {
        return null;
    }
}
