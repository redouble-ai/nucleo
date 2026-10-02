/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.registry.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the parse-side half of the tool_use/tool_result pairing invariant:
 * {@link ThinkingResponseHandler#parse(List)} must turn EVERY native tool_use block into
 * exactly one {@link ToolCall}, even when the named tool is not registered. Before the fix
 * an unregistered tool made parse throw, which abandoned the whole turn's tool_use blocks -
 * they were already recorded on the assistant turn, so the next request 400'd with
 * "tool_use ids were found without tool_result blocks". Keeping the call lets executeTools
 * answer it with an LLM-readable error and the pairing stays intact. The text path has no
 * pairing to keep, so there a call the runtime cannot route - a nameless one included - is
 * refused at parse with a correctable error the correction loop replays.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-15)
 */
public class ThinkingResponseHandlerToolUseTest {

    public static class StubInput {
        public String value;
    }

    public static class StubOutput {
        public String result;
    }

    @ToolName("known_tool")
    @ToolDescription("A registered stub tool used only for parse resolution")
    public static class KnownTool extends AbstractTool<StubInput, StubOutput> {
        public KnownTool(Identifiable parent) {
            super(parent);
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = new JobRequirements();
            req.setRequiresTransaction(false);
            return req;
        }

        @Override
        public StubOutput execute(JobResources resources, JobContext<StubOutput> context) {
            throw new UnsupportedOperationException("not executed in a parse-only test");
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ThinkingResponseHandler<String> handler(ToolRegistry registry) {
        return new ThinkingResponseHandler<>(registry,
                new PojoResponseHandler<ThinkingResponse<String>>((Class)ThinkingResponse.class),
                StringResponseHandler.instance);
    }

    @Test
    void unregisteredToolUseStillBecomesAToolCallInsteadOfAbortingTheBatch() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(KnownTool.class);
        List<ContentBlock> blocks = List.of(
                new ToolUseBlock("toolu_known", "known_tool", "{\"value\":\"x\"}"),
                new ToolUseBlock("toolu_ghost", "ghost_tool", "{\"foo\":1}"));

        ThinkingResponse<String> response = handler(registry).parse(blocks);

        List<ToolCall> calls = response.getToolCalls();
        assertEquals(2, calls.size(), "every tool_use block must yield a ToolCall, including the unregistered one");
        ToolCall known = calls.stream().filter(c -> "toolu_known".equals(c.getToolUseId())).findFirst().orElseThrow();
        ToolCall ghost = calls.stream().filter(c -> "toolu_ghost".equals(c.getToolUseId())).findFirst().orElseThrow();
        assertEquals("known_tool", known.getToolName());
        assertEquals("ghost_tool", ghost.getToolName());
        assertTrue(known.getInput() instanceof StubInput, "registered tool input is parsed to its typed POJO");
        assertNotNull(ghost.getInput(), "unregistered tool keeps its raw input so the call can still be answered");
        assertFalse(response.isFinalAnswer(), "a turn carrying tool calls is not a final answer");
    }

    @Test
    void aCallParsedFromTextGetsAnIdItsResultCanBeRecordedAgainst() throws Exception {
        // A dialect with no native tool channel: the model writes its calls into the JSON
        // envelope, so nothing upstream assigns an id, and a result needs one to be kept.
        ToolRegistry registry = new ToolRegistry();
        registry.register(KnownTool.class);
        ThinkingResponse<String> response = handler(registry).parse(
                "{ \"final_answer\": false, \"tool_calls\": ["
                        + " { \"tool_name\": \"known_tool\", \"input\": { \"value\": \"x\" } },"
                        + " { \"tool_name\": \"known_tool\", \"input\": { \"value\": \"y\" } } ] }");
        List<ToolCall> calls = response.getToolCalls();
        assertEquals(2, calls.size());
        assertEquals("known_tool#1", calls.get(0).getToolUseId());
        assertEquals("known_tool#2", calls.get(1).getToolUseId(), "the same tool twice in one turn gets distinct ids");
        assertTrue(calls.get(0).getInput() instanceof StubInput);
    }

    @Test
    void aNamelessCallFromTextIsRefusedCorrectably_neverAsANullKeyCrash() {
        // Seen live: a model answering the JSON envelope on a wire surface it barely speaks
        // wrote a tool_calls entry with no tool_name. The name is the model's own data, so
        // the refusal must be LLM-readable for the correction loop to replay - never a
        // NullPointerException out of the registry's backing map.
        ToolRegistry registry = new ToolRegistry();
        registry.register(KnownTool.class);
        assertThrows(InvalidInputException.class, () -> handler(registry).parse(
                "{ \"final_answer\": false, \"tool_calls\": [ { \"input\": { \"value\": \"x\" } } ] }"),
                "a nameless call is an unregistered tool, refused so the model can fix its call");
    }

    @Test
    void allUnregisteredToolUsesArePreservedSoNoneAreOrphaned() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        List<ContentBlock> blocks = List.of(
                new ToolUseBlock("toolu_a", "ghost_a", "{}"),
                new ToolUseBlock("toolu_b", "ghost_b", "{}"),
                new ToolUseBlock("toolu_c", "ghost_c", "{}"));

        ThinkingResponse<String> response = handler(registry).parse(blocks);

        Set<String> ids = new HashSet<>();
        for (ToolCall c : response.getToolCalls()) {
            ids.add(c.getToolUseId());
        }
        assertEquals(Set.of("toolu_a", "toolu_b", "toolu_c"), ids,
                "a batch of unregistered tool_use blocks must all survive parse, not be abandoned by a throw");
    }
}
