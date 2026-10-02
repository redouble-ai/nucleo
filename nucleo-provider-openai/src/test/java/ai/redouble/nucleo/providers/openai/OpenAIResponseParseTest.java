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
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterization of how {@link OpenAICompatibleClient} maps the provider's response body onto
 * {@link LLMResponse}. Counterpart of {@link OpenAIRequestShapeTest} for the inbound
 * direction: id and served-model echo, the subset-shaped usage block
 * ({@code prompt_tokens_details.cached_tokens} inside {@code prompt_tokens}), finish-reason
 * normalization, and content extraction - including a minimal body where every optional
 * is absent.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-30)
 */
public class OpenAIResponseParseTest {

    private static final String FULL_RESPONSE = """
            {"id":"chatcmpl-123","model":"gpt-5-mini-2026-01-15",
             "usage":{"prompt_tokens":120,"completion_tokens":30,
                      "prompt_tokens_details":{"cached_tokens":100}},
             "choices":[{"finish_reason":"stop",
                         "message":{"role":"assistant","content":"ANSWER-MARKER"}}]}""";

    @Test
    void fullResponseMapsEveryField() {
        LLMResponse<String> response = parse(FULL_RESPONSE);
        assertEquals("chatcmpl-123", response.getResponseMessage().getMessageId());
        assertEquals("gpt-5-mini-2026-01-15", response.getServedModelId(),
                "the served-model echo: an alias answers with its snapshot");
        assertEquals(120, response.getActualInputTokens(),
                "prompt_tokens is the grand total on this provider");
        assertEquals(100, response.getCacheReadInputTokens(),
                "cached_tokens is a subset of prompt_tokens, not additive");
        assertEquals(30, response.getResponseMessage().getActualOutputTokens());
        assertEquals(LLMStopReason.END_TURN, response.getStopReason(),
                "finish_reason \"stop\" normalizes to END_TURN");
        assertEquals("ANSWER-MARKER", response.getResponseMessage().getRawContent());
    }

    @Test
    void nativeToolCallsBecomeToolUseBlocksWithTheProvidersIds() {
        LLMResponse<String> response = parse("""
                {"choices":[{"finish_reason":"tool_calls",
                             "message":{"role":"assistant","content":null,
                                        "tool_calls":[{"id":"call_abc","type":"function",
                                                       "function":{"name":"probe_tool","arguments":"{\\"q\\":\\"ports\\"}"}},
                                                      {"id":"call_def","type":"function",
                                                       "function":{"name":"other_tool","arguments":"{}"}}]}}]}""");
        assertEquals(LLMStopReason.TOOL_USE, response.getStopReason());
        List<ContentBlocks.ContentBlock> blocks = response.getResponseMessage().getContentBlocks();
        assertEquals(2, blocks.size(), "a null content adds no text block; every call is one block");
        ContentBlocks.ToolUseBlock first = (ContentBlocks.ToolUseBlock) blocks.get(0);
        assertEquals("call_abc", first.toolUseId(), "the provider's id is the one the result is recorded against");
        assertEquals("probe_tool", first.toolName());
        assertEquals("{\"q\":\"ports\"}", first.inputJson());
        assertEquals("call_def", ((ContentBlocks.ToolUseBlock) blocks.get(1)).toolUseId());
        assertEquals("", response.getResponseMessage().getRawContent());
    }

    @Test
    void textAndCallsTogetherKeepTheTextFirst() {
        LLMResponse<String> response = parse("""
                {"choices":[{"finish_reason":"tool_calls",
                             "message":{"content":"Looking that up.",
                                        "tool_calls":[{"id":"call_1","type":"function","function":{"name":"t","arguments":"{}"}}]}}]}""");
        List<ContentBlocks.ContentBlock> blocks = response.getResponseMessage().getContentBlocks();
        assertEquals("Looking that up.", ((ContentBlocks.TextBlock) blocks.get(0)).text());
        assertEquals("call_1", ((ContentBlocks.ToolUseBlock) blocks.get(1)).toolUseId());
        assertEquals("Looking that up.", response.getResponseMessage().getRawContent());
    }

    @Test
    void truncationFinishReasonNormalizesToMaxTokens() {
        LLMResponse<String> response = parse(
                "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"cut\"}}]}");
        assertEquals(LLMStopReason.MAX_TOKENS, response.getStopReason(),
                "\"length\" is the truncation signal the abstract client's retry keys on");
    }

    @Test
    void minimalResponseParsesWithAbsentOptionals() {
        // The least a body can carry and still be an answer: one choice with a message
        LLMResponse<String> response = parse("{\"choices\":[{\"message\":{\"content\":\"x\"}}]}");
        assertEquals("", response.getResponseMessage().getMessageId());
        assertNull(response.getServedModelId());
        assertNull(response.getActualInputTokens(), "no usage block, no usage recorded");
        assertEquals(LLMStopReason.UNKNOWN, response.getStopReason());
    }

    @Test
    void nonJsonBodyFailsLoud() {
        assertThrows(RuntimeException.class, () -> parse("gateway error page"));
    }

    @Test
    void anEmptyBodyFailsLoudLikeAnyOtherNonAnswer() {
        assertThrows(UncorrectableRuntimeLLMException.class, () -> parse(""), "an empty body parses to nothing and is refused, not read as an empty answer");
        assertThrows(UncorrectableRuntimeLLMException.class, () -> parse("   "));
    }

    // ---- strict about the missing: a body that is JSON but carries no answer ----

    /** Bodies that are valid JSON and are not an answer: the reader refuses each rather than reading an empty one. */
    static List<String> bodiesWithNoAnswer() {
        return List.of(
                "{\"error\":{\"message\":\"upstream trouble\",\"type\":\"server_error\"}}",
                "{\"id\":\"chatcmpl-1\",\"choices\":[]}",
                "{\"id\":\"chatcmpl-1\",\"choices\":[{\"finish_reason\":\"stop\"}]}",
                "[]",
                "null",
                "\"ok\"",
                "42");
    }

    @TestFactory
    Collection<DynamicTest> aBodyWithNoChoiceIsRefusedNotReadAsAnEmptyAnswer() {
        List<DynamicTest> tests = new ArrayList<>();
        for (String body : bodiesWithNoAnswer()) {
            tests.add(DynamicTest.dynamicTest(body, () -> assertThrows(UncorrectableRuntimeLLMException.class, () -> parse(body),
                    "a 200 whose body carries no choice with a message is not an answer and never becomes a successful empty one")));
        }
        return tests;
    }

    @Test
    void contentSentAsPartsIsReadAsItsText() {
        // Several endpoints that speak the dialect answer with content as an array of typed parts
        LLMResponse<String> response = parse("""
                {"choices":[{"finish_reason":"stop",
                             "message":{"role":"assistant","content":[{"type":"text","text":"ANSWER-"},{"type":"text","text":"MARKER"}]}}]}""");
        assertEquals("ANSWER-MARKER", response.getResponseMessage().getRawContent(), "the text of the parts, joined, is the answer");
    }

    @Test
    void aNullContentWithoutCallsIsAnEmptyAnswer() {
        // The dialect's own shape for a turn with nothing to say: content null, no calls
        LLMResponse<String> response = parse("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":null}}]}");
        assertEquals("", response.getResponseMessage().getRawContent());
        assertEquals(LLMStopReason.END_TURN, response.getStopReason());
    }

    @Test
    void aToolCallWithoutAnIdOrANameIsRefused() {
        // A call with no id cannot be answered - the result is recorded against the id and the next
        // request echoes it - and a call with no name cannot be run
        assertThrows(UncorrectableRuntimeLLMException.class, () -> parse("""
                {"choices":[{"finish_reason":"tool_calls","message":{"content":null,
                   "tool_calls":[{"type":"function","function":{"name":"probe_tool","arguments":"{}"}}]}}]}"""), "no id");
        assertThrows(UncorrectableRuntimeLLMException.class, () -> parse("""
                {"choices":[{"finish_reason":"tool_calls","message":{"content":null,
                   "tool_calls":[{"id":"call_1","type":"function","function":{"arguments":"{}"}}]}}]}"""), "no name");
        assertThrows(UncorrectableRuntimeLLMException.class, () -> parse("""
                {"choices":[{"finish_reason":"tool_calls","message":{"content":null,
                   "tool_calls":[{"id":"call_1","type":"custom","custom":{"name":"probe_tool","input":"x"}}]}}]}"""), "not a function call");
    }

    @Test
    void argumentsThatAreNotJsonAreKeptAsWrittenForTheToolLoopToJudge() {
        // The model wrote them; the thinker's parse answers the call with a correctable error rather
        // than the reader deciding on the model's behalf
        LLMResponse<String> response = parse("""
                {"choices":[{"finish_reason":"tool_calls","message":{"content":null,
                   "tool_calls":[{"id":"call_1","type":"function","function":{"name":"probe_tool","arguments":"{\\"q\\": "}}]}}]}""");
        ContentBlocks.ToolUseBlock call = (ContentBlocks.ToolUseBlock) response.getResponseMessage().getContentBlocks().get(0);
        assertEquals("{\"q\": ", call.inputJson());
    }

    @Test
    void usageWithStringNumbersOrNullIsReadOrAbsentNeverZeroed() {
        LLMResponse<String> strings = parse("{\"choices\":[{\"message\":{\"content\":\"x\"}}],\"usage\":{\"prompt_tokens\":\"12\",\"completion_tokens\":\"3\"}}");
        assertEquals(12, strings.getActualInputTokens(), "a number written as a string is still the number");
        assertEquals(3, strings.getResponseMessage().getActualOutputTokens());
        LLMResponse<String> absent = parse("{\"choices\":[{\"message\":{\"content\":\"x\"}}],\"usage\":null}");
        assertNull(absent.getActualInputTokens(), "a null usage block records nothing, so nothing downstream is billed at zero");
        assertNull(absent.getResponseMessage().getActualOutputTokens());
    }

    @Test
    void anUnknownFinishReasonIsUnknownNotTruncation() {
        LLMResponse<String> response = parse("{\"choices\":[{\"finish_reason\":\"vendor_specific\",\"message\":{\"content\":\"x\"}}]}");
        assertEquals(LLMStopReason.UNKNOWN, response.getStopReason());
        assertFalse(response.wasTruncated(), "a reason this process does not know is not a truncation signal");
    }

    private LLMResponse<String> parse(String body) {
        ModelSpec model = TestModels.onProvider("openai");
        ConversationContext conversation = TestModels.conversation(model);
        OutgoingMessage<String> outgoing = new OutgoingMessage<>(StringResponseHandler.instance);
        outgoing.setRole("user");
        outgoing.addText("USER-MARKER");
        conversation.getMessages().add(outgoing);
        OpenAICompatibleClient client = new OpenAICompatibleClient(WireApi.CHAT_COMPLETIONS);
        client.setModel(model);
        LLMRequest<String> request = new LLMRequest<>(conversation);
        return client.finishResponse(new LLMResponse<>(request), request, body);
    }
}
