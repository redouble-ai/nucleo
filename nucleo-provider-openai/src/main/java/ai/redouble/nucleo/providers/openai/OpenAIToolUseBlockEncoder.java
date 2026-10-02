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
 * Chat Completions native tool call: one element of an assistant message's {@code tool_calls}
 * array, carrying the provider's own call id, the function name and the arguments as the
 * JSON text the model wrote. A tool call is not content on this dialect but message
 * structure, so the message assembly routes what this encoder produces into the assistant
 * message's {@code tool_calls} rather than its {@code content}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
public class OpenAIToolUseBlockEncoder extends ToolUseBlockEncoder<ObjectNode> {
    @Override
    public ObjectNode encode(ContentBlock block) {
        ToolUseBlock use = (ToolUseBlock) block;
        ObjectNode call = NucleoJsonSerializer.createObjectNode();
        call.put("id", use.toolUseId());
        call.put("type", "function");
        ObjectNode function = call.putObject("function");
        function.put("name", use.toolName());
        function.put("arguments", use.inputJson());
        return call;
    }
}
