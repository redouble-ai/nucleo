/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.observability.*;
import ai.redouble.nucleo.harness.tokens.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.openai.core.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where the OpenAI clients put prompt-cache breakpoints, on both dialects, asserted on the
 * request JSON. A model the catalog gives {@code cache_breakpoints} is sent
 * {@code prompt_cache_options.mode: explicit} and a {@code prompt_cache_breakpoint} on the
 * content parts the placement chooses, so nothing is stored but what the conversation's
 * messages allow; a model without them is sent neither and caches by itself. What each
 * message states is its {@link Cacheability}.
 *
 * @author Andrey Santrosyan
 * @since 0.1.3 (2026-11-11)
 */
class OpenAICacheBreakpointTest {
    private static final String OBJECTIVE = "OBJECTIVE-MARKER extract the port labels";
    private static final String BREAKPOINT = "prompt_cache_breakpoint";

    /** An OpenAI entry of the catalog that takes cache breakpoints. */
    private static ModelSpec marked() {
        return Models.all().stream().filter(spec -> "openai".equals(spec.getProviderKey()) && spec.getCacheBreakpoints() > 0).findFirst().orElseThrow();
    }

    /** An OpenAI entry of the catalog that caches by itself. */
    private static ModelSpec selfCaching() {
        return Models.all().stream().filter(spec -> "openai".equals(spec.getProviderKey()) && spec.getCacheBreakpoints() == 0).findFirst().orElseThrow();
    }

    private static ConversationContext conversation(ModelSpec model, boolean cacheTurns) {
        ConversationContext conversation = TestModels.conversation(model);
        conversation.setDepth(Depth.IMMEDIATE);
        conversation.setOutputDeclaration(OutputDeclaration.of(1000));
        conversation.setCacheTurns(cacheTurns);
        conversation.putMainObjective("task", OBJECTIVE);
        return conversation;
    }

    private static OutgoingMessage<String> turn(String role, String text) {
        OutgoingMessage<String> message = new OutgoingMessage<>(StringResponseHandler.instance);
        message.setRole(role);
        message.addText(text);
        return message;
    }

    private static OutgoingMessage<String> turn(String role, String text, Cacheability cacheability) {
        OutgoingMessage<String> message = turn(role, text);
        message.setCacheability(cacheability);
        return message;
    }

    /** User and assistant turns alternating, the first and the last a user turn: {@code U1 A1 U2 ... Un}. */
    private static void addExchange(ConversationContext conversation, int userTurns) {
        for (int i = 1; i <= userTurns; i++) {
            conversation.getMessages().add(turn("user", "U" + i + "-MARKER"));
            if (i < userTurns) {
                conversation.getMessages().add(turn("assistant", "A" + i + "-MARKER"));
            }
        }
    }

    private static ObjectNode chat(ConversationContext conversation) {
        OpenAICompatibleClient client = new OpenAICompatibleClient(WireApi.CHAT_COMPLETIONS);
        client.setModel(conversation.getModel());
        return client.buildRequestJson(new LLMRequest<>(conversation), LlmTestDoors.prepare(client, conversation));
    }

    private static ObjectNode responses(ConversationContext conversation) {
        OpenAISDKClient client = new OpenAISDKClient(WireApi.RESPONSES, "unused");
        client.setModel(conversation.getModel());
        return client.buildResponsesJson(new LLMRequest<>(conversation), LlmTestDoors.prepare(client, conversation));
    }

    /** The markers of the messages or input items that carry a breakpoint, in wire order. */
    private static List<String> marked(JsonNode items, String... markers) {
        List<String> found = new ArrayList<>();
        for (JsonNode item : items) {
            if (!item.toString().contains(BREAKPOINT)) {
                continue;
            }
            String name = "?";
            for (String marker : markers) {
                if (item.toString().contains(marker)) {
                    name = marker;
                }
            }
            found.add(name);
        }
        return found;
    }

    private static int breakpoints(JsonNode request) {
        return request.toString().split(BREAKPOINT, -1).length - 1;
    }

    // ---------------------------------------------------------------- a model that caches by itself

    @Test
    void aModelWithoutCacheBreakpointsIsSentNoCacheField_onEitherDialect() {
        ConversationContext conversation = conversation(selfCaching(), true);
        addExchange(conversation, 3);
        ObjectNode chat = chat(conversation);
        assertFalse(chat.has("prompt_cache_options"), "the model caches by itself, in the provider's own mode");
        assertEquals(0, breakpoints(chat));
        assertTrue(chat.get("messages").get(0).get("content").isTextual(), "the system message stays one string");
        ObjectNode responses = responses(conversation);
        assertFalse(responses.has("prompt_cache_options"));
        assertEquals(0, breakpoints(responses));
        assertTrue(responses.get("instructions").asText().contains(OBJECTIVE), "the system content stays the instructions");
    }

    // ---------------------------------------------------------------- Chat Completions

    @Test
    void aModelWithCacheBreakpointsIsSentExplicitMode_andTheSystemContentEndsTheFirstStoredPrefix() {
        ConversationContext conversation = conversation(marked(), false);
        addExchange(conversation, 1);
        ObjectNode request = chat(conversation);
        assertEquals("explicit", request.get("prompt_cache_options").get("mode").asText(), "nothing is stored but what a breakpoint marks");
        JsonNode system = request.get("messages").get(0);
        assertEquals("system", system.get("role").asText());
        JsonNode part = system.get("content").get(0);
        assertEquals("text", part.get("type").asText(), "the content string becomes the one text part that can carry a breakpoint");
        assertTrue(part.get("text").asText().contains(OBJECTIVE));
        assertEquals("explicit", part.get(BREAKPOINT).get("mode").asText());
        assertEquals(1, breakpoints(request), "a conversation that does not cache its turns stores the system content and nothing else");
    }

    @Test
    void aConversationThatDoesNotCacheItsSystemContentCarriesNoBreakpointOnIt() {
        ConversationContext conversation = conversation(marked(), false);
        conversation.setCacheMainObjective(false);
        addExchange(conversation, 1);
        ObjectNode request = chat(conversation);
        assertEquals("explicit", request.get("prompt_cache_options").get("mode").asText(), "the mode is the model's, whatever the conversation marks");
        assertTrue(request.get("messages").get(0).get("content").isTextual());
        assertEquals(0, breakpoints(request), "nothing is marked, so nothing is stored");
    }

    @Test
    void aGrowingConversationMarksItsEnd_theTurnBeforeIt_andItsFirstTurn() {
        ConversationContext conversation = conversation(marked(), true);
        addExchange(conversation, 4);
        JsonNode messages = chat(conversation).get("messages");
        assertEquals(List.of(OBJECTIVE, "U1-MARKER", "U3-MARKER", "U4-MARKER"), marked(messages, OBJECTIVE, "U1-MARKER", "U2-MARKER", "U3-MARKER", "U4-MARKER"),
                "the system content, the original request, the end of the previous request so this one reads what that one stored, and the end");
        JsonNode last = messages.get(messages.size() - 1);
        assertEquals("user", last.get("role").asText());
        assertEquals("explicit", last.get("content").get(last.get("content").size() - 1).get(BREAKPOINT).get("mode").asText(), "on the last part of the turn, so the stored prefix holds all of it");
    }

    @Test
    void anAssistantTurnCarriesNoBreakpoint_evenWhenItStatesAlways() {
        ConversationContext conversation = conversation(marked(), false);
        conversation.getMessages().add(turn("user", "U1-MARKER"));
        conversation.getMessages().add(turn("assistant", "A1-MARKER", Cacheability.ALWAYS));
        conversation.getMessages().add(turn("user", "U2-MARKER"));
        JsonNode messages = chat(conversation).get("messages");
        assertEquals(List.of(OBJECTIVE), marked(messages, OBJECTIVE, "U1-MARKER", "A1-MARKER", "U2-MARKER"), "only a user turn can carry one on this provider");
    }

    @Test
    void aTurnNamedPreferredOrAlwaysIsMarkedInAConversationThatDoesNotCacheItsTurns() {
        for (Cacheability named : List.of(Cacheability.PREFERRED, Cacheability.ALWAYS)) {
            ConversationContext conversation = conversation(marked(), false);
            conversation.getMessages().add(turn("user", "U1-MARKER"));
            conversation.getMessages().add(turn("assistant", "A1-MARKER"));
            conversation.getMessages().add(turn("user", "U2-MARKER", named));
            conversation.getMessages().add(turn("assistant", "A2-MARKER"));
            conversation.getMessages().add(turn("user", "U3-MARKER"));
            JsonNode messages = chat(conversation).get("messages");
            assertEquals(List.of(OBJECTIVE, "U2-MARKER"), marked(messages, OBJECTIVE, "U1-MARKER", "U2-MARKER", "U3-MARKER"), named + " is a statement by name; the other turns are UNLIKELY and end nothing");
        }
    }

    @Test
    void nothingIsMarkedOnOrAfterANeverTurn() {
        ConversationContext conversation = conversation(marked(), true);
        conversation.getMessages().add(turn("user", "U1-MARKER"));
        conversation.getMessages().add(turn("assistant", "A1-MARKER"));
        conversation.getMessages().add(turn("user", "U2-MARKER"));
        conversation.getMessages().add(turn("assistant", "A2-MARKER"));
        conversation.getMessages().add(turn("user", "U3-MARKER", Cacheability.NEVER));
        conversation.getMessages().add(turn("assistant", "A3-MARKER"));
        conversation.getMessages().add(turn("user", "U4-MARKER", Cacheability.ALWAYS));
        ObjectNode request = chat(conversation);
        assertEquals("explicit", request.get("prompt_cache_options").get("mode").asText(), "explicit mode is what keeps the provider from storing the turn by itself");
        assertEquals(List.of(OBJECTIVE, "U1-MARKER", "U2-MARKER"), marked(request.get("messages"), OBJECTIVE, "U1-MARKER", "U2-MARKER", "U3-MARKER", "U4-MARKER"),
                "a prefix ending on the turn or after it would contain it");
    }

    @Test
    void anUnlikelyLastTurnLeavesTheEndOnTheTurnBeforeIt() {
        ConversationContext conversation = conversation(marked(), true);
        conversation.getMessages().add(turn("user", "U1-MARKER"));
        conversation.getMessages().add(turn("assistant", "A1-MARKER"));
        conversation.getMessages().add(turn("user", "U2-MARKER"));
        conversation.getMessages().add(turn("assistant", "A2-MARKER"));
        conversation.getMessages().add(turn("user", "U3-MARKER", Cacheability.UNLIKELY));
        JsonNode messages = chat(conversation).get("messages");
        assertEquals(List.of(OBJECTIVE, "U1-MARKER", "U2-MARKER"), marked(messages, OBJECTIVE, "U1-MARKER", "U2-MARKER", "U3-MARKER"));
    }

    @Test
    void everyAlwaysTurnIsMarked_whateverTheirNumber() {
        ConversationContext conversation = conversation(marked(), false);
        for (int i = 1; i <= 6; i++) {
            conversation.getMessages().add(turn("user", "U" + i + "-MARKER", Cacheability.ALWAYS));
            conversation.getMessages().add(turn("assistant", "A" + i + "-MARKER"));
        }
        conversation.getMessages().add(turn("user", "U7-MARKER"));
        ObjectNode request = chat(conversation);
        assertEquals(7, breakpoints(request), "the system content and all six: what OpenAI does with more than it writes per request is the caller's to see");
    }

    @Test
    void aTurnOfToolResultsOnlyIsMarkedOnItsLastResult() {
        ConversationContext conversation = conversation(marked(), false);
        conversation.getMessages().add(turn("user", "U1-MARKER"));
        IncomingMessage<String> calls = new IncomingMessage<>(StringResponseHandler.instance);
        calls.setContentBlocks(List.of(new ToolUseBlock("call_1", "probe_tool", "{}"), new ToolUseBlock("call_2", "probe_tool", "{}")));
        conversation.getMessages().add(calls);
        OutgoingMessage<String> results = new OutgoingMessage<>(null);
        results.setRole("user");
        results.addToolResult("call_1", "RESULT-1-MARKER", false);
        results.addToolResult("call_2", "RESULT-2-MARKER", false);
        results.setCacheability(Cacheability.ALWAYS);
        conversation.getMessages().add(results);
        conversation.getMessages().add(turn("assistant", "A2-MARKER"));
        conversation.getMessages().add(turn("user", "U2-MARKER"));
        JsonNode messages = chat(conversation).get("messages");
        assertEquals(List.of(OBJECTIVE, "RESULT-2-MARKER"), marked(messages, OBJECTIVE, "RESULT-1-MARKER", "RESULT-2-MARKER", "U2-MARKER"));
        JsonNode tool = messages.get(4);
        assertEquals("tool", tool.get("role").asText());
        assertEquals("call_2", tool.get("tool_call_id").asText());
        assertEquals("text", tool.get("content").get(0).get("type").asText(), "the result string becomes the one text part that can carry a breakpoint");
        assertEquals("RESULT-2-MARKER", tool.get("content").get(0).get("text").asText());
        assertTrue(messages.get(3).get("content").isTextual(), "a result that carries none stays a string");
    }

    @Test
    void aTurnThatEndsInAnImageIsMarkedOnTheImage() throws IOException {
        ConversationContext conversation = conversation(marked(), true);
        OutgoingMessage<String> picture = new OutgoingMessage<>(StringResponseHandler.instance);
        picture.setRole("user");
        picture.addText("U1-MARKER");
        picture.addImage(new ByteArrayInputStream(Base64.getDecoder().decode("aGk=")), "image/png", "a pixel");
        conversation.getMessages().add(picture);
        JsonNode messages = chat(conversation).get("messages");
        JsonNode content = messages.get(messages.size() - 1).get("content");
        JsonNode lastPart = content.get(content.size() - 1);
        assertTrue(lastPart.has(BREAKPOINT), "the last part of the turn carries it, whatever its type: " + lastPart.get("type").asText());
        assertEquals(2, breakpoints(messages), "the system content and the turn");
    }

    // ---------------------------------------------------------------- Responses

    @Test
    void onResponses_theSystemContentLeadsTheInputAsADeveloperMessage_becauseInstructionsCarryNoBreakpoint() {
        ConversationContext conversation = conversation(marked(), false);
        addExchange(conversation, 1);
        ObjectNode request = responses(conversation);
        assertEquals("explicit", request.get("prompt_cache_options").get("mode").asText());
        assertFalse(request.has("instructions"), "the same content is not sent twice");
        JsonNode developer = request.get("input").get(0);
        assertEquals("message", developer.get("type").asText());
        assertEquals("developer", developer.get("role").asText());
        JsonNode part = developer.get("content").get(0);
        assertEquals("input_text", part.get("type").asText());
        assertTrue(part.get("text").asText().contains(OBJECTIVE));
        assertEquals("explicit", part.get(BREAKPOINT).get("mode").asText());
        assertEquals(1, breakpoints(request));
    }

    @Test
    void onResponses_aConversationThatDoesNotCacheItsSystemContentKeepsTheInstructions() {
        ConversationContext conversation = conversation(marked(), false);
        conversation.setCacheMainObjective(false);
        addExchange(conversation, 1);
        ObjectNode request = responses(conversation);
        assertTrue(request.get("instructions").asText().contains(OBJECTIVE));
        assertEquals("user", request.get("input").get(0).get("role").asText());
        assertEquals(0, breakpoints(request));
    }

    @Test
    void onResponses_aGrowingConversationMarksItsEnd_theTurnBeforeIt_andItsFirstTurn() {
        ConversationContext conversation = conversation(marked(), true);
        addExchange(conversation, 4);
        JsonNode input = responses(conversation).get("input");
        assertEquals(List.of(OBJECTIVE, "U1-MARKER", "U3-MARKER", "U4-MARKER"), marked(input, OBJECTIVE, "U1-MARKER", "U2-MARKER", "U3-MARKER", "U4-MARKER"));
        for (JsonNode item : input) {
            if ("assistant".equals(item.path("role").asText())) {
                assertFalse(item.toString().contains(BREAKPOINT), "an assistant's output text cannot carry one");
            }
        }
    }

    @Test
    void onResponses_aTurnOfToolResultsOnlyIsMarkedOnItsLastResult_andOneWithTextOnTheText() {
        ConversationContext conversation = conversation(marked(), false);
        conversation.getMessages().add(turn("user", "U1-MARKER"));
        IncomingMessage<String> calls = new IncomingMessage<>(StringResponseHandler.instance);
        calls.setContentBlocks(List.of(new ToolUseBlock("call_1", "probe_tool", "{}")));
        conversation.getMessages().add(calls);
        OutgoingMessage<String> bare = new OutgoingMessage<>(null);
        bare.setRole("user");
        bare.addToolResult("call_1", "RESULT-1-MARKER", false);
        bare.setCacheability(Cacheability.ALWAYS);
        conversation.getMessages().add(bare);
        IncomingMessage<String> more = new IncomingMessage<>(StringResponseHandler.instance);
        more.setContentBlocks(List.of(new ToolUseBlock("call_2", "probe_tool", "{}")));
        conversation.getMessages().add(more);
        OutgoingMessage<String> withText = new OutgoingMessage<>(StringResponseHandler.instance);
        withText.setRole("user");
        withText.addToolResult("call_2", "RESULT-2-MARKER", false);
        withText.addText("U2-MARKER");
        withText.setCacheability(Cacheability.ALWAYS);
        conversation.getMessages().add(withText);
        JsonNode input = responses(conversation).get("input");
        assertEquals(List.of(OBJECTIVE, "RESULT-1-MARKER", "U2-MARKER"), marked(input, OBJECTIVE, "RESULT-1-MARKER", "RESULT-2-MARKER", "U2-MARKER"));
        JsonNode first = null;
        JsonNode second = null;
        for (JsonNode item : input) {
            if ("call_1".equals(item.path("call_id").asText()) && "function_call_output".equals(item.path("type").asText())) {
                first = item;
            }
            if ("call_2".equals(item.path("call_id").asText()) && "function_call_output".equals(item.path("type").asText())) {
                second = item;
            }
        }
        assertEquals("input_text", first.get("output").get(0).get("type").asText(), "the output string becomes the one text part that can carry a breakpoint");
        assertEquals("RESULT-1-MARKER", first.get("output").get(0).get("text").asText());
        assertTrue(first.get("output").get(0).has(BREAKPOINT));
        assertTrue(second.get("output").isTextual(), "the turn's text follows its result, so the text ends the turn and the result stays a string");
    }

    // ---------------------------------------------------------------- the SDK's typed parameters

    @Test
    void theSdkParametersCarryTheModeAndEveryBreakpoint_onEitherDialect() {
        ConversationContext conversation = conversation(marked(), true);
        addExchange(conversation, 3);
        ObjectNode chat = chat(conversation);
        JsonNode chatBody = ObjectMappers.jsonMapper().valueToTree(OpenAISDKClient.toParams(chat)._body());
        assertEquals("explicit", chatBody.path("prompt_cache_options").path("mode").asText(), "the mode rides as a body property of its own");
        assertEquals(breakpoints(chat), breakpoints(chatBody), "the conversion to typed messages loses no breakpoint");
        assertEquals(4, breakpoints(chatBody));
        ObjectNode responses = responses(conversation);
        JsonNode responsesBody = ObjectMappers.jsonMapper().valueToTree(OpenAISDKClient.toResponsesParams(responses)._body());
        assertEquals("explicit", responsesBody.path("prompt_cache_options").path("mode").asText());
        assertEquals(breakpoints(responses), breakpoints(responsesBody), "the conversion to typed input items loses no breakpoint");
        assertEquals(4, breakpoints(responsesBody));
    }

    // ---------------------------------------------------------------- usage

    @Test
    void theTokensACallStoredAreReadFromTheUsage_onEitherDialect() {
        ConversationContext conversation = conversation(marked(), true);
        addExchange(conversation, 1);
        OpenAICompatibleClient chatClient = new OpenAICompatibleClient(WireApi.CHAT_COMPLETIONS);
        chatClient.setModel(conversation.getModel());
        LLMRequest<String> chatRequest = new LLMRequest<>(conversation);
        LLMResponse<String> chat = chatClient.finishResponse(new LLMResponse<>(chatRequest), chatRequest, """
                {"id":"chatcmpl-1","model":"m","usage":{"prompt_tokens":5000,"completion_tokens":30,
                 "prompt_tokens_details":{"cached_tokens":3000,"cache_write_tokens":1900}},
                 "choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"ok"}}]}""");
        assertEquals(5000, chat.getUsage().inputTokens(), "the prompt count is the whole input");
        assertEquals(3000, chat.getUsage().cacheReadTokens());
        assertEquals(1900, chat.getUsage().cacheWriteTokens(), "the part of the input this call stored");
        assertEquals(100, chat.getUsage().uncachedInputTokens(), "what is left was neither read from the cache nor written to it");
        OpenAISDKClient responsesClient = new OpenAISDKClient(WireApi.RESPONSES, "unused");
        responsesClient.setModel(conversation.getModel());
        LLMRequest<String> responsesRequest = new LLMRequest<>(conversation);
        LLMResponse<String> responses = responsesClient.finishResponses(new LLMResponse<>(responsesRequest), responsesRequest, """
                {"id":"resp_1","model":"m","status":"completed",
                 "output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"ok"}]}],
                 "usage":{"input_tokens":5000,"input_tokens_details":{"cached_tokens":3000,"cache_write_tokens":1900},"output_tokens":30}}""");
        assertEquals(3000, responses.getUsage().cacheReadTokens());
        assertEquals(1900, responses.getUsage().cacheWriteTokens());
        assertEquals(100, responses.getUsage().uncachedInputTokens());
    }

    // ---------------------------------------------------------------- the catalog

    @Test
    void theShippedCatalogGivesBreakpointsToTheModelsThatTakeThem_andNoneToTheRest() {
        assertEquals(4, Models.spec("gpt-5.6").getCacheBreakpoints());
        assertEquals(4, Models.spec("gpt-6-astra").getCacheBreakpoints());
        assertEquals(0, Models.spec("gpt-5").getCacheBreakpoints(), "a model that caches by itself");
        assertEquals(4, Models.spec("gpt-5-6-sol-azure").getCacheBreakpoints(), "a Foundry deployment of such a model takes them too");
        assertEquals(0, Models.spec("gpt-5-5-azure").getCacheBreakpoints(), "a Foundry deployment of a model that caches by itself");
        for (String id : List.of("gpt-5.6", "gpt-6-astra", "gpt-6-astra-azure", "gpt-5-6-sol-azure", "gpt-5-6-terra-azure", "gpt-5-6-luna-azure")) {
            ModelSpec spec = Models.spec(id);
            assertEquals(1.25 * spec.getInputPricePerMillion(), spec.getCacheWritePricePerMillion(), 1e-9, id + " states the price of a cache write, which its provider bills above the input price");
        }
    }

    @Test
    void aCallThatStoredTokensIsPricedAtTheCacheWritePrice_notAsPlainInput() {
        ModelSpec spec = Models.spec("gpt-5-6-sol-azure");
        // 1M tokens of input: 600K stored, 300K read, 100K neither; no output
        TokenLedger usage = new OpenAITokenLedger(spec, 1_000_000, 300_000, 600_000, 0);
        Cost cost = Cost.of(spec, usage.inputTokens(), usage.cacheWriteTokens(), usage.cacheReadTokens(), usage.outputTokens());
        assertEquals(0.6 * 5.0 + 0.3 * 0.40 + 0.1 * 4.0, cost.amount(), 1e-9, "writes at 5.00, reads at 0.40 and the rest at 4.00 per million");
    }
}
