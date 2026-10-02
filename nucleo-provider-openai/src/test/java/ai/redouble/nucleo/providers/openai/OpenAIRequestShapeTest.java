/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterization of the wire request {@link OpenAICompatibleClient} builds. Third member of the
 * comparison with the Anthropic and Bedrock artifacts' {@code AnthropicRequestShapeTest} and {@code BedrockConverseRequestShapeTest}:
 * same conversation, same channel contract.
 *
 * <p>OpenAI has no separate system channel, so the system content - the main objective -
 * is expressed as a {@code role:system} message leading the array. That is this provider's
 * way of carrying instructions; the content and its exactly-once arrival are the contract.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-23)
 */
public class OpenAIRequestShapeTest {

    private static final String OBJECTIVE = "OBJECTIVE-MARKER extract the port labels";
    private static final String USER_TURN = "USER-MARKER Ringports changed to 5 and 7.";
    private static final String ASSISTANT_TURN = "ASSISTANT-MARKER previous answer";
    private static final String TOOL_NAME = "TOOL-MARKER-probe_tool";
    /** The output the canonical conversation declares. */
    private static final int TEST_DEFAULT = 16_000;

    @Test
    void objectiveLeadsTheArrayAsTheOnlySystemMessage_exactlyOnce() {
        JsonNode messages = build().get("messages");

        assertEquals(1, count(messages, OBJECTIVE),
                "the objective reaches the wire exactly once");
        assertEquals("system", roleOf(messages, OBJECTIVE),
                "as system content, expressed the way this provider carries it");
        assertEquals(0, indexOf(messages, OBJECTIVE),
                "at the front, where OpenAI's automatic prefix caching keys");
        assertEquals(3, messages.size(),
                "every prepared part reaches the wire: system (objective), assistant turn, user turn");
    }

    @Test
    void initialPaletteReachesTheWireExactlyOnce_asTheToolsParameter() {
        ObjectNode request = build();

        assertEquals(0, count(request.get("messages"), TOOL_NAME),
                "no message carries the definition as text");
        JsonNode tools = request.get("tools");
        assertEquals(1, tools.size(), "the definition is one entry of the native tools parameter");
        assertEquals("function", tools.get(0).get("type").asText());
        assertEquals(TOOL_NAME, tools.get(0).get("function").get("name").asText());
        assertEquals("finds things", tools.get(0).get("function").get("description").asText());
        assertEquals("object", tools.get(0).get("function").get("parameters").get("type").asText(),
                "the definition's JSON Schema is the function's parameters");
    }

    @Test
    void aSchemaHandlerBindsTheAnswerToAJsonObject_aTextHandlerDoesNot() throws Exception {
        assertFalse(build().has("response_format"), "a plain-text handler leaves the answer free text");
        ModelSpec model = TestModels.onProvider("openai");
        ConversationContext conversation = TestModels.conversation(model);
        conversation.setDepth(Depth.IMMEDIATE);
        conversation.setOutputDeclaration(OutputDeclaration.of(TEST_DEFAULT));
        OutgoingMessage<Map> ask = new OutgoingMessage<>(new PojoResponseHandler<>(Map.class));
        ask.setRole("user");
        ask.addText(USER_TURN);
        conversation.getMessages().add(ask);
        OpenAICompatibleClient client = new OpenAICompatibleClient(WireApi.CHAT_COMPLETIONS);
        client.setModel(model);
        ObjectNode request = client.buildRequestJson(new LLMRequest<>(conversation), LlmTestDoors.prepare(client, conversation));
        assertEquals("json_object", request.get("response_format").get("type").asText(),
                "a handler that describes its answer as a schema asks for one JSON object, and the dialect holds the model to it");
    }

    @Test
    void aToolCallAndItsResultAreMessageStructure() {
        ModelSpec model = TestModels.onProvider("openai");
        ConversationContext conversation = TestModels.conversation(model);
        conversation.setDepth(Depth.IMMEDIATE);
        conversation.setOutputDeclaration(OutputDeclaration.of(TEST_DEFAULT));
        conversation.getMessages().add(message("user", USER_TURN));
        IncomingMessage<String> call = new IncomingMessage<>(StringResponseHandler.instance);
        call.setContentBlocks(List.of(new ContentBlocks.ToolUseBlock("call_1", TOOL_NAME, "{\"q\":\"ports\"}")));
        conversation.getMessages().add(call);
        OutgoingMessage<String> results = new OutgoingMessage<>(StringResponseHandler.instance);
        results.setRole("user");
        results.addToolResult("call_1", "{\"labels\":[5,7]}", false);
        results.addText("RESULTS-TEXT-MARKER");
        conversation.getMessages().add(results);
        OpenAICompatibleClient client = new OpenAICompatibleClient(WireApi.CHAT_COMPLETIONS);
        client.setModel(model);
        JsonNode messages = client.buildRequestJson(new LLMRequest<>(conversation), LlmTestDoors.prepare(client, conversation)).get("messages");

        JsonNode assistant = messages.get(indexOf(messages, "call_1"));
        assertEquals("assistant", assistant.get("role").asText());
        assertFalse(assistant.has("content"), "a turn that is only calls has no content");
        JsonNode toolCall = assistant.get("tool_calls").get(0);
        assertEquals("call_1", toolCall.get("id").asText(), "the provider's own call id goes back");
        assertEquals(TOOL_NAME, toolCall.get("function").get("name").asText());
        assertEquals("{\"q\":\"ports\"}", toolCall.get("function").get("arguments").asText(), "the arguments as the JSON text the model wrote");
        JsonNode result = messages.get(indexOf(messages, "call_1") + 1);
        assertEquals("tool", result.get("role").asText(), "the result is a role:tool message directly after the call");
        assertEquals("call_1", result.get("tool_call_id").asText());
        assertEquals("{\"labels\":[5,7]}", result.get("content").asText());
        JsonNode remainder = messages.get(indexOf(messages, "RESULTS-TEXT-MARKER"));
        assertEquals("user", remainder.get("role").asText(), "what else the turn says follows as the user message");
        assertEquals(indexOf(messages, "call_1") + 2, indexOf(messages, "RESULTS-TEXT-MARKER"));
    }

    @Test
    void userAndAssistantRolesArePreserved() {
        JsonNode messages = build().get("messages");

        assertEquals("user", roleOf(messages, USER_TURN), "a user turn stays a user turn");
        assertEquals("assistant", roleOf(messages, ASSISTANT_TURN), "an assistant turn stays an assistant turn");
    }

    @Test
    void maxTokensIsTheDeclaredOutputPlusReasoningHeadroom() {
        ModelSpec model = TestModels.onProvider("openai");
        ObjectNode request = build();
        assertEquals(ThinkingMode.REASONING_EFFORT, model.getThinkingMode(),
                "fixture assumption: the OpenAI entries are the reasoning generation");
        assertEquals(TEST_DEFAULT + model.getThinkingBudget(Depth.IMMEDIATE), request.get("max_completion_tokens").asInt(),
                "the wire ceiling is the declared answer plus the entry's reasoning headroom, because the"
                        + " reasoning generation reasons inside max_completion_tokens - the legacy max_tokens"
                        + " name is rejected by every model in the catalog");
        // The canonical conversation carries a tool, so a model declared tools_suspend_reasoning
        // (the GPT-5.6 family) sends none on this turn; every other model reasons at the floor
        assertEquals(model.toolsSuspendReasoning() ? "none" : "low", request.get("reasoning_effort").asText(),
                "IMMEDIATE maps to the floor effort, unless the model refuses tools with a reasoning effort");
    }

    @Test
    void modelIsTheSpecsWireId() {
        assertEquals(TestModels.onProvider("openai").getWireModelId(), build().get("model").asText(),
                "OpenAI is addressed by the spec's wire id");
    }

    @Test
    void temperatureTravelsOnlyWhenACallerSetOne() {
        assertFalse(build().has("temperature"),
                "unset sends nothing: the provider's own default applies, and models that accept no sampling parameter stay callable");
        assertEquals(0.2, build(0.2).get("temperature").asDouble(),
                "a caller-set temperature reaches the wire, and the provider's verdict on it is the caller's to receive");
    }

    /** Same canonical conversation as the other two shape tests. */
    private ObjectNode build() {
        return build(null);
    }

    private ObjectNode build(Double temperature) {
        ModelSpec model = TestModels.onProvider("openai");
        ConversationContext conversation = TestModels.conversation(model);
        conversation.setDepth(Depth.IMMEDIATE);
        conversation.setOutputDeclaration(OutputDeclaration.of(TEST_DEFAULT));
        conversation.putMainObjective("task", OBJECTIVE);
        conversation.addTool(new ai.redouble.nucleo.harness.conversation.ContentBlocks.ToolDefinitionBlock(
                TOOL_NAME, "finds things", "{\"type\":\"object\"}"));
        conversation.getMessages().add(message("assistant", ASSISTANT_TURN));
        conversation.getMessages().add(message("user", USER_TURN));
        OpenAICompatibleClient client = new OpenAICompatibleClient(WireApi.CHAT_COMPLETIONS);
        client.setModel(model);
        client.setTemperature(temperature);
        PreparedConversation prepared = LlmTestDoors.prepare(client, conversation);
        return client.buildRequestJson(new LLMRequest<>(conversation), prepared);
    }

    private static OutgoingMessage<String> message(String role, String text) {
        OutgoingMessage<String> message = new OutgoingMessage<>(StringResponseHandler.instance);
        message.setRole(role);
        message.addText(text);
        return message;
    }

    private static int count(JsonNode messages, String marker) {
        int found = 0;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).toString().contains(marker)) {
                found++;
            }
        }
        return found;
    }

    private static int indexOf(JsonNode messages, String marker) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).toString().contains(marker)) {
                return i;
            }
        }
        throw new AssertionError("no message carries " + marker);
    }

    private static String roleOf(JsonNode messages, String marker) {
        return messages.get(indexOf(messages, marker)).get("role").asText();
    }
}
