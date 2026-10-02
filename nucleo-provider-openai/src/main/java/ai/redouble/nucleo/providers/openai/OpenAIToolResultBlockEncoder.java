/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.llm.encode.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.node.*;

/**
 * Chat Completions native tool result: a whole {@code role:tool} message answering one call by
 * its id. On this dialect a result is not a content element inside the user turn, as it is on
 * Anthropic, but a message of its own that must directly follow the assistant message whose
 * {@code tool_calls} it answers, so the message assembly emits what this encoder produces as
 * a message ahead of the turn's remaining user content. The dialect has no error flag on a
 * result; an error result carries its LLM-readable text as content like any other.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
public class OpenAIToolResultBlockEncoder extends ToolResultBlockEncoder<ObjectNode> {
    @Override
    public ObjectNode encode(ContentBlock block) {
        ToolResultBlock result = (ToolResultBlock) block;
        ObjectNode message = NucleoJsonSerializer.createObjectNode();
        message.put("role", "tool");
        message.put("tool_call_id", result.toolUseId());
        message.put("content", result.resultJson());
        return message;
    }
}
