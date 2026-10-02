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
import com.fasterxml.jackson.databind.node.*;
import com.openai.core.*;
import com.openai.core.http.*;
import com.openai.errors.*;
import com.openai.models.*;
import com.openai.models.chat.completions.*;
import com.openai.models.completions.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The SDK transport without a network: the dialect's JSON becomes the SDK's typed parameters
 * with nothing lost, a typed completion round-trips into the shared body reader, the response
 * headers become the account's limits, and the SDK's typed failures classify the way the
 * framework expects.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
class OpenAISDKClientTest {

    private static ModelSpec model() {
        return TestModels.onProvider("openai");
    }

    private static LLMResponse<String> emptyResponse() {
        ConversationContext conversation = TestModels.conversation(model());
        OutgoingMessage<String> message = new OutgoingMessage<>(StringResponseHandler.instance);
        message.setRole("user");
        message.addText("ping");
        conversation.getMessages().add(message);
        return new LLMResponse<>(new LLMRequest<>(conversation));
    }

    private static OpenAISDKClient client() {
        OpenAISDKClient client = new OpenAISDKClient(WireApi.CHAT_COMPLETIONS, "unused");
        client.setModel(model());
        return client;
    }

    @Test
    void theRequestJsonBecomesTypedParamsWithNothingLost() throws Exception {
        ObjectNode json = (ObjectNode) NucleoJsonSerializer.readTree("""
                { "model": "gpt-5", "max_completion_tokens": 4096, "temperature": 1.0, "reasoning_effort": "low",
                  "messages": [
                    { "role": "system", "content": "be brief" },
                    { "role": "user", "content": [ { "type": "text", "text": "hi" },
                                                   { "type": "image_url", "image_url": { "url": "data:image/png;base64,aGk=" } } ] },
                    { "role": "assistant", "content": "hello" },
                    { "role": "user", "content": "bye" }
                  ] }
                """);
        ChatCompletionCreateParams params = OpenAISDKClient.toParams(json);
        assertEquals("gpt-5", params.model().asString());
        assertEquals(4096L, params.maxCompletionTokens().orElseThrow());
        assertEquals(ReasoningEffort.LOW, params.reasoningEffort().orElseThrow());
        List<ChatCompletionMessageParam> messages = params.messages();
        assertEquals(4, messages.size());
        assertTrue(messages.get(0).isSystem());
        assertTrue(messages.get(1).isUser());
        assertTrue(messages.get(1).asUser().content().isArrayOfContentParts(), "multimodal content stays an array");
        assertEquals(2, messages.get(1).asUser().content().asArrayOfContentParts().size());
        assertTrue(messages.get(2).isAssistant());
        assertEquals("bye", messages.get(3).asUser().content().asText());
    }

    @Test
    void toolsAndToolMessagesBecomeTypedParams() throws Exception {
        ObjectNode json = (ObjectNode) NucleoJsonSerializer.readTree("""
                { "model": "gpt-5", "max_completion_tokens": 4096, "temperature": 1.0,
                  "response_format": { "type": "json_object" },
                  "tools": [ { "type": "function", "function": { "name": "probe_tool", "description": "finds things",
                                                                 "parameters": { "type": "object", "properties": { "q": { "type": "string" } } } } } ],
                  "messages": [
                    { "role": "user", "content": "find ports" },
                    { "role": "assistant", "tool_calls": [ { "id": "call_1", "type": "function",
                                                             "function": { "name": "probe_tool", "arguments": "{\\"q\\":\\"ports\\"}" } } ] },
                    { "role": "tool", "tool_call_id": "call_1", "content": "{\\"labels\\":[5,7]}" }
                  ] }
                """);
        ChatCompletionCreateParams params = OpenAISDKClient.toParams(json);
        assertTrue(params.responseFormat().orElseThrow().isJsonObject(), "the answer shape rides as the typed response format");
        List<ChatCompletionTool> tools = params.tools().orElseThrow();
        assertEquals(1, tools.size());
        assertTrue(tools.get(0).isFunction());
        assertEquals("probe_tool", tools.get(0).asFunction().function().name());
        assertTrue(tools.get(0).asFunction().function().parameters().isPresent(), "the JSON Schema rides as the parameters");
        List<ChatCompletionMessageParam> messages = params.messages();
        assertTrue(messages.get(1).isAssistant());
        assertEquals("call_1", messages.get(1).asAssistant().toolCalls().orElseThrow().get(0).asFunction().id());
        assertTrue(messages.get(2).isTool(), "a result is a tool message");
        assertEquals("call_1", messages.get(2).asTool().toolCallId());
        assertEquals("{\"labels\":[5,7]}", messages.get(2).asTool().content().asText());
    }

    @Test
    void streamedToolCallDeltasGatherIntoWholeCalls() {
        SortedMap<Long, ObjectNode> calls = new TreeMap<>();
        OpenAISDKClient.gather(calls, delta(0, "call_a", "probe_tool", "{\"q\":"));
        OpenAISDKClient.gather(calls, delta(1, "call_b", "other_tool", "{"));
        OpenAISDKClient.gather(calls, delta(0, null, null, "\"ports\"}"));
        OpenAISDKClient.gather(calls, delta(1, null, null, "}"));
        assertEquals(2, calls.size());
        assertEquals("call_a", calls.get(0L).get("id").asText());
        assertEquals("probe_tool", calls.get(0L).get("function").get("name").asText());
        assertEquals("{\"q\":\"ports\"}", calls.get(0L).get("function").get("arguments").asText(), "arguments arrive in pieces and are joined in order");
        assertEquals("{}", calls.get(1L).get("function").get("arguments").asText());
        assertEquals("other_tool", calls.get(1L).get("function").get("name").asText());
    }

    @Test
    void deltasArrivingOutOfIndexOrderStillGatherByIndex() {
        SortedMap<Long, ObjectNode> calls = new TreeMap<>();
        OpenAISDKClient.gather(calls, delta(1, "call_b", "other_tool", "{}"));
        OpenAISDKClient.gather(calls, delta(0, "call_a", "probe_tool", "{}"));
        assertEquals(List.of(0L, 1L), new ArrayList<>(calls.keySet()), "calls are ordered by the index the provider gave them, not by arrival");
        assertEquals("call_a", calls.get(0L).get("id").asText());
    }

    @Test
    void aStreamedCallThatNeverGotItsIdOrNameIsRefusedWhenTheStreamEnds() {
        // The id and the name ride the first delta of a call; a stream that ends without them has
        // handed over a call that cannot be answered or run, and the client says so rather than
        // handing the thinker a call with a blank id
        SortedMap<Long, ObjectNode> noId = new TreeMap<>();
        OpenAISDKClient.gather(noId, delta(0, null, "probe_tool", "{}"));
        assertThrows(UncorrectableRuntimeLLMException.class, () -> OpenAISDKClient.gathered(noId), "no id");
        SortedMap<Long, ObjectNode> noName = new TreeMap<>();
        OpenAISDKClient.gather(noName, delta(0, "call_a", null, "{}"));
        assertThrows(UncorrectableRuntimeLLMException.class, () -> OpenAISDKClient.gathered(noName), "no name");
        SortedMap<Long, ObjectNode> whole = new TreeMap<>();
        OpenAISDKClient.gather(whole, delta(0, "call_a", "probe_tool", "{}"));
        assertEquals("call_a", OpenAISDKClient.gathered(whole).get(0).toolUseId());
    }

    private static ChatCompletionChunk.Choice.Delta.ToolCall delta(long index, String id, String name, String arguments) {
        ChatCompletionChunk.Choice.Delta.ToolCall.Function.Builder function = ChatCompletionChunk.Choice.Delta.ToolCall.Function.builder().arguments(arguments);
        if (name != null) {
            function.name(name);
        }
        ChatCompletionChunk.Choice.Delta.ToolCall.Builder delta = ChatCompletionChunk.Choice.Delta.ToolCall.builder().index(index).function(function.build());
        if (id != null) {
            delta.id(id);
        }
        return delta.build();
    }

    @Test
    void aTypedCompletionRoundTripsIntoTheSharedBodyReader() {
        ChatCompletion completion = ChatCompletion.builder()
                .id("chatcmpl-1")
                .created(1L)
                .model("gpt-5-2025-08-07")
                .addChoice(ChatCompletion.Choice.builder()
                        .index(0L)
                        .finishReason(ChatCompletion.Choice.FinishReason.LENGTH)
                        .logprobs(Optional.empty())
                        .message(ChatCompletionMessage.builder().content("pong").refusal(Optional.empty()).build())
                        .build())
                .usage(CompletionUsage.builder().promptTokens(7L).completionTokens(1L).totalTokens(8L)
                        .promptTokensDetails(CompletionUsage.PromptTokensDetails.builder().cachedTokens(5L).build())
                        .build())
                .build();
        LLMResponse<String> response = emptyResponse();
        client().finishResponse(response, response.getRequest(), ObjectMappers.jsonMapper().valueToTree(completion).toString());
        assertEquals("chatcmpl-1", response.getResponseMessage().getMessageId());
        assertEquals("gpt-5-2025-08-07", response.getServedModelId());
        assertEquals(LLMStopReason.MAX_TOKENS, response.getStopReason());
        assertEquals(7, response.getActualInputTokens());
        assertEquals(1, response.getActualOutputTokens());
        assertEquals("pong", response.getResponseMessage().getRawContent());
    }

    @Test
    void responseHeadersBecomeTheAccountsLimits() {
        LLMResponse<String> response = emptyResponse();
        client().captureEnvelope(response, Headers.builder()
                .put("X-RateLimit-Limit-Tokens", "40000000")
                .put("x-ratelimit-limit-requests", "15000")
                .put("x-request-id", "req-1")
                .build());
        assertEquals("req-1", response.getProviderRequestId());
        assertEquals(40000000, response.getRateLimitInfo().getTokensLimit());
        assertEquals(15000, response.getRateLimitInfo().getRequestsLimit());
        assertEquals("40000000", response.getProviderHeaders().get("x-ratelimit-limit-tokens"), "header names are lowercased");
    }

    @Test
    void typedFailuresClassifyAsTheFrameworkExpects() {
        OpenAISDKClient client = client();
        RateLimitException throttled = RateLimitException.builder().headers(Headers.builder().put("retry-after", "7").build()).build();
        assertTrue(client.is429Error(throttled));
        assertFalse(client.isQuotaError(throttled));
        assertEquals(7, client.extractRateLimitInfo(throttled).getRetryAfter().toSeconds());
        RateLimitException broke = RateLimitException.builder().headers(Headers.builder().build())
                .error(ErrorObject.builder().code("insufficient_quota").message("out").type("insufficient_quota").param(Optional.empty()).build())
                .build();
        assertTrue(client.isQuotaError(broke));
        InternalServerException down = InternalServerException.builder().statusCode(503).headers(Headers.builder().build()).build();
        assertTrue(client.isServerError(new RuntimeException("wrapped", down)));
        assertFalse(client.is429Error(down));
    }
}
