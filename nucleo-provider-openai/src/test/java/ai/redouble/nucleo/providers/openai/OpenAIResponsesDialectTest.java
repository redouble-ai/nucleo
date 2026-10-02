/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.openai.models.*;
import com.openai.models.responses.*;
import org.junit.jupiter.api.*;
import java.io.*;
import java.time.*;
import java.util.*;
import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterization of the Responses dialect, both directions: the request
 * {@code buildResponsesJson} assembles from the canonical conversation, its conversion into the
 * SDK's typed parameters with nothing lost, and the response body {@code finishResponses} reads
 * back onto the framework's response.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class OpenAIResponsesDialectTest {
    private static final String OBJECTIVE = "OBJECTIVE-MARKER extract the port labels";
    private static final String REASONING_ITEM = "{\"type\":\"reasoning\",\"id\":\"rs_1\",\"summary\":[],\"encrypted_content\":\"ENCRYPTED-MARKER\"}";

    private static OpenAISDKClient client() {
        OpenAISDKClient client = new OpenAISDKClient(WireApi.RESPONSES, "unused");
        client.setModel(TestModels.onProvider("openai"));
        return client;
    }

    /** A tool loop mid-flight: the objective, a tool, a prior assistant turn that reasoned and called, the user turn answering it with text and an image. */
    private static ConversationContext loop() throws IOException {
        ConversationContext conversation = TestModels.conversation(TestModels.onProvider("openai"));
        conversation.setDepth(Depth.IMMEDIATE);
        conversation.setOutputDeclaration(OutputDeclaration.of(1000));
        conversation.putMainObjective("task", OBJECTIVE);
        conversation.addTool(new ToolDefinitionBlock("probe_tool", "finds things", "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}"));
        // The model's earlier turn as the reader stored it: its reasoning, its text and its call, in that order
        IncomingMessage<String> earlier = new IncomingMessage<>(StringResponseHandler.instance);
        earlier.setContentBlocks(List.of(new RedactedThinkingBlock(REASONING_ITEM), new TextBlock("Looking that up."),
                new ToolUseBlock("call_1", "probe_tool", "{\"q\":\"ports\"}")));
        conversation.getMessages().add(earlier);
        OutgoingMessage<String> answer = new OutgoingMessage<>(StringResponseHandler.instance);
        answer.setRole("user");
        answer.setTimestamp(Instant.now());
        answer.addToolResult("call_1", "{\"labels\":[5,7]}", false);
        answer.addImage(new ByteArrayInputStream(Base64.getDecoder().decode("aGk=")), "image/png", "a pixel");
        answer.addText("USER-MARKER and what about this?");
        conversation.getMessages().add(answer);
        return conversation;
    }

    private static ObjectNode build(ConversationContext conversation) {
        OpenAISDKClient client = client();
        return client.buildResponsesJson(new LLMRequest<>(conversation), LlmTestDoors.prepare(client, conversation));
    }

    @Test
    void theRequestCarriesInstructionsToolsReasoningAndTheTurnsAsItems() throws IOException {
        ObjectNode request = build(loop());
        assertEquals(TestModels.onProvider("openai").getWireModelId(), request.get("model").asText());
        assertFalse(request.get("store").asBoolean(), "the conversation is the runtime's; nothing is stored server-side");
        assertTrue(request.get("instructions").asText().contains(OBJECTIVE), "the objective is the instructions");
        assertEquals("low", request.get("reasoning").get("effort").asText(), "IMMEDIATE maps to the floor effort, beside the tools");
        assertEquals("reasoning.encrypted_content", request.get("include").get(0).asText(), "encrypted reasoning comes back for the replay");
        JsonNode tool = request.get("tools").get(0);
        assertEquals("function", tool.get("type").asText());
        assertEquals("probe_tool", tool.get("name").asText(), "a flat function, not nested under a function object");
        assertFalse(tool.get("strict").asBoolean(), "strict mode is off: the runtime's schemas are not written for it");
        assertEquals("object", tool.get("parameters").get("type").asText());
        List<String> types = new ArrayList<>();
        request.get("input").forEach(item -> types.add(item.get("type").asText()));
        assertEquals(List.of("reasoning", "message", "function_call", "function_call_output", "message"), types,
                "the assistant's reasoning, text and call in the order produced; then the result ahead of the user's own content");
        JsonNode reasoning = request.get("input").get(0);
        assertEquals("ENCRYPTED-MARKER", reasoning.get("encrypted_content").asText(), "the reasoning item replays verbatim");
        JsonNode assistant = request.get("input").get(1);
        assertEquals("assistant", assistant.get("role").asText());
        assertEquals("output_text", assistant.get("content").get(0).get("type").asText());
        JsonNode call = request.get("input").get(2);
        assertEquals("call_1", call.get("call_id").asText());
        assertEquals("{\"q\":\"ports\"}", call.get("arguments").asText());
        JsonNode result = request.get("input").get(3);
        assertEquals("call_1", result.get("call_id").asText());
        assertEquals("{\"labels\":[5,7]}", result.get("output").asText());
        JsonNode user = request.get("input").get(4);
        assertEquals("user", user.get("role").asText());
        assertEquals("input_image", user.get("content").get(0).get("type").asText());
        assertTrue(user.get("content").get(0).get("image_url").asText().startsWith("data:image/png;base64,"));
        assertEquals("input_text", user.get("content").get(1).get("type").asText());
        assertTrue(user.get("content").get(1).get("text").asText().contains("USER-MARKER"));
    }

    @Test
    void theRequestJsonBecomesTypedParamsWithNothingLost() throws IOException {
        ResponseCreateParams params = OpenAISDKClient.toResponsesParams(build(loop()));
        assertEquals(TestModels.onProvider("openai").getWireModelId(), params.model().orElseThrow().asString());
        assertFalse(params.store().orElseThrow());
        assertTrue(params.instructions().orElseThrow().contains(OBJECTIVE));
        assertEquals(ReasoningEffort.LOW, params.reasoning().orElseThrow().effort().orElseThrow());
        assertEquals(1, params.include().orElseThrow().size());
        assertEquals("probe_tool", params.tools().orElseThrow().get(0).asFunction().name());
        List<ResponseInputItem> items = params.input().orElseThrow().asResponse();
        assertEquals(5, items.size());
        assertTrue(items.get(0).isReasoning());
        assertTrue(items.get(2).isFunctionCall());
        assertTrue(items.get(3).isFunctionCallOutput());
    }

    @Test
    void aSchemaHandlerBindsTheAnswerToAJsonObject() {
        ConversationContext conversation = TestModels.conversation(TestModels.onProvider("openai"));
        conversation.setDepth(Depth.IMMEDIATE);
        conversation.setOutputDeclaration(OutputDeclaration.of(1000));
        OutgoingMessage<Map> ask = new OutgoingMessage<>(new PojoResponseHandler<>(Map.class));
        ask.setRole("user");
        ask.addText("answer as JSON");
        conversation.getMessages().add(ask);
        assertEquals("json_object", build(conversation).get("text").get("format").get("type").asText());
    }

    @Test
    void theOutputItemsBecomeBlocksAndTheUsageAndStopReasonAreRead() {
        LLMResponse<String> response = read("""
                {"id":"resp_1","model":"gpt-5.6-sol-2026-07-09","status":"completed",
                 "output":[
                   {"type":"reasoning","id":"rs_1","summary":[{"type":"summary_text","text":"thinking about ports"}],"encrypted_content":"ENC"},
                   {"type":"message","role":"assistant","content":[{"type":"output_text","text":"Looking "},{"type":"output_text","text":"that up."}]},
                   {"type":"function_call","call_id":"call_1","name":"probe_tool","arguments":"{\\"q\\":\\"ports\\"}","id":"fc_1"}
                 ],
                 "usage":{"input_tokens":120,"input_tokens_details":{"cached_tokens":100},"output_tokens":30,"output_tokens_details":{"reasoning_tokens":20}}}
                """);
        assertEquals("resp_1", response.getResponseMessage().getMessageId());
        assertEquals("gpt-5.6-sol-2026-07-09", response.getServedModelId());
        assertEquals("Looking that up.", response.getResponseMessage().getRawContent(), "the message's text parts joined");
        List<ContentBlock> blocks = response.getResponseMessage().getContentBlocks();
        assertInstanceOf(TextBlock.class, blocks.get(0), "the text leads");
        assertInstanceOf(RedactedThinkingBlock.class, blocks.get(1));
        assertTrue(((RedactedThinkingBlock) blocks.get(1)).data().contains("\"encrypted_content\":\"ENC\""), "the whole reasoning item is kept for the replay");
        ToolUseBlock call = (ToolUseBlock) blocks.get(2);
        assertEquals("call_1", call.toolUseId());
        assertEquals("probe_tool", call.toolName());
        assertEquals("{\"q\":\"ports\"}", call.inputJson());
        assertEquals(120, response.getActualInputTokens());
        assertEquals(100, response.getCacheReadInputTokens());
        assertEquals(30, response.getActualOutputTokens());
        assertEquals(LLMStopReason.TOOL_USE, response.getStopReason());
    }

    @Test
    void anIncompleteResponseOnTheOutputBudgetIsTheTruncation() {
        LLMResponse<String> response = read("""
                {"id":"resp_2","status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},
                 "output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"The bicy"}]}],
                 "usage":{"input_tokens":10,"output_tokens":16}}
                """);
        assertEquals(LLMStopReason.MAX_TOKENS, response.getStopReason());
        assertEquals("The bicy", response.getResponseMessage().getRawContent());
    }

    @Test
    void aFailedResponseAndABodyWithoutOutputAreRefused() {
        assertThrows(UncorrectableRuntimeLLMException.class, () -> read("{\"id\":\"resp_3\",\"status\":\"failed\",\"output\":[],\"error\":{\"message\":\"boom\"}}"));
        assertThrows(UncorrectableRuntimeLLMException.class, () -> read("{\"error\":{\"message\":\"gateway\"}}"));
        assertThrows(UncorrectableRuntimeLLMException.class, () -> read(""));
        assertThrows(UncorrectableRuntimeLLMException.class, () -> read("{\"output\":[{\"type\":\"function_call\",\"name\":\"probe_tool\",\"arguments\":\"{}\"}]}"),
                "a call without an id cannot be answered");
    }

    private static LLMResponse<String> read(String body) {
        ConversationContext conversation = TestModels.conversation(TestModels.onProvider("openai"));
        OutgoingMessage<String> ask = new OutgoingMessage<>(StringResponseHandler.instance);
        ask.setRole("user");
        ask.addText("ping");
        conversation.getMessages().add(ask);
        LLMRequest<String> request = new LLMRequest<>(conversation);
        return client().finishResponses(new LLMResponse<>(request), request, body);
    }
}
