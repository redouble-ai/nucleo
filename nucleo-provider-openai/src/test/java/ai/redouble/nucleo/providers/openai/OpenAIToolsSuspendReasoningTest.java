/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A model the catalog declares {@code tools_suspend_reasoning} refuses function tools together
 * with a reasoning effort, pinned from the live refusal of 2026-09-16 on OpenAI and on Foundry
 * alike ({@code 400 Function tools with reasoning_effort are not supported for gpt-5.6 in
 * /v1/chat/completions ... set reasoning_effort to 'none'}): a turn that carries tools goes out
 * with the effort at {@code none}, a turn without tools keeps the depth's effort, and a model
 * without the declaration sends the depth's effort with tools as before.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class OpenAIToolsSuspendReasoningTest {

    @Test
    void aDeclaredModelSendsNoReasoningEffortWithTools() {
        ObjectNode request = build(true, true);
        assertTrue(request.has("tools"), "fixture assumption: the tool definition reaches the tools parameter");
        assertEquals("none", request.get("reasoning_effort").asText());
    }

    @Test
    void aDeclaredModelKeepsTheDepthsEffortWithoutTools() {
        ObjectNode request = build(true, false);
        assertFalse(request.has("tools"));
        assertEquals("low", request.get("reasoning_effort").asText(), "IMMEDIATE maps to the floor effort");
    }

    @Test
    void anUndeclaredModelSendsTheDepthsEffortWithTools() {
        ObjectNode request = build(false, true);
        assertTrue(request.has("tools"));
        assertEquals("low", request.get("reasoning_effort").asText(), "GPT-5 takes tools and a reasoning effort together");
    }

    private static ObjectNode build(boolean declared, boolean withTool) {
        ModelSpec fixture = TestModels.onProvider("openai");
        StandardModelSpec model = NucleoJsonSerializer.convert(NucleoJsonSerializer.valueToTree(fixture), StandardModelSpec.class);
        model.setToolsSuspendReasoning(declared);
        ConversationContext conversation = TestModels.conversation(model);
        conversation.setDepth(Depth.IMMEDIATE);
        conversation.setOutputDeclaration(OutputDeclaration.of(1000));
        conversation.putMainObjective("task", "answer");
        if (withTool) {
            conversation.addTool(new ContentBlocks.ToolDefinitionBlock("probe_tool", "finds things", "{\"type\":\"object\"}"));
        }
        OutgoingMessage<String> ask = new OutgoingMessage<>(StringResponseHandler.instance);
        ask.setRole("user");
        ask.addText("what time is it");
        conversation.getMessages().add(ask);
        OpenAISDKClient client = new OpenAISDKClient(WireApi.CHAT_COMPLETIONS);
        client.setModel(model);
        return client.buildRequestJson(new LLMRequest<>(conversation), LlmTestDoors.prepare(client, conversation));
    }
}
