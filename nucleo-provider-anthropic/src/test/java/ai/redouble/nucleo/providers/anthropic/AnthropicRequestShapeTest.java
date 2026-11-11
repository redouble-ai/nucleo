/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.anthropic;

import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import com.anthropic.models.messages.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterization of the wire request {@link AnthropicSDKClient} builds, sitting beside
 * the Bedrock and OpenAI artifacts' {@code BedrockConverseRequestShapeTest} and {@code OpenAIRequestShapeTest} so the three
 * can be read together: same conversation, same channel contract, three dialects.
 *
 * <p>Built through the protected subclass constructor, which skips the credential read, so
 * the request builder is exercised with no secrets present.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-23)
 */
public class AnthropicRequestShapeTest {

    private static final String OBJECTIVE = "OBJECTIVE-MARKER extract the port labels";
    private static final String USER_TURN = "USER-MARKER Ringports changed to 5 and 7.";
    private static final String INJECTED_TURN = "INJECTED-MARKER also check port 9.";
    private static final String ASSISTANT_TURN = "ASSISTANT-MARKER previous answer";
    private static final String TOOL_NAME = "TOOL-MARKER-probe_tool";
    /** The output the canonical conversation declares. */
    private static final int TEST_DEFAULT = 16_000;

    @Test
    void objectiveReachesTheWireExactlyOnce_inTheSystemParameter() {
        MessageCreateParams params = build(true);

        assertTrue(systemText(params).contains(OBJECTIVE),
                "the objective is the system content");
        assertEquals(0, countInTurns(params, OBJECTIVE),
                "and is not also a turn");
    }

    @Test
    void userAndAssistantRolesArePreserved() {
        MessageCreateParams params = build(true);

        assertEquals(MessageParam.Role.USER, roleOfTurnContaining(params, USER_TURN),
                "a user turn stays a user turn");
        assertEquals(MessageParam.Role.ASSISTANT, roleOfTurnContaining(params, ASSISTANT_TURN),
                "an assistant turn stays an assistant turn");
    }

    @Test
    void systemPrefixCarriesTheCacheBreakpoint_whenTheConversationAsksForIt() {
        assertTrue(lastSystemBlockIsCached(build(true)),
                "cacheMainObjective=true marks the system prefix cacheable");
        assertTrue(!lastSystemBlockIsCached(build(false)),
                "cacheMainObjective=false leaves the prefix unmarked - the flag decides,"
                        + " not the mere existence of system content");
    }

    private static boolean lastSystemBlockIsCached(MessageCreateParams params) {
        var blocks = params.system().orElseThrow().asTextBlockParams();
        return blocks.get(blocks.size() - 1).cacheControl().isPresent();
    }

    @Test
    void consecutiveUserTurnsReachTheWireUncoalescedAndInOrder() {
        // the injection wire shape: a follow-up steered into a running exchange rides as
        // its own user turn right after the previous user turn (same shape the closing
        // instruction ships), and the encoder must neither merge nor reorder the pair
        MessageCreateParams params = build(true, conversation ->
                conversation.getMessages().add(message("user", INJECTED_TURN)));

        List<MessageParam> turns = params.messages();
        int first = indexOfTurnContaining(turns, USER_TURN);
        int second = indexOfTurnContaining(turns, INJECTED_TURN);
        assertEquals(first + 1, second, "the injected turn directly follows the triggering user turn");
        assertEquals(MessageParam.Role.USER, turns.get(first).role());
        assertEquals(MessageParam.Role.USER, turns.get(second).role());
        assertEquals(1, countInTurns(params, INJECTED_TURN), "carried exactly once, never merged into another turn");
    }

    @Test
    void maxTokensIsTheResolvedBudget_withThinkingOff() {
        MessageCreateParams params = build(true);

        assertEquals(TEST_DEFAULT, params.maxTokens(),
                "with effort NONE the wire ceiling is the plain output budget, no thinking folded in");
    }

    @Test
    void toolDefinitionTravelsNativelyExactlyOnce_neverAsText() {
        MessageCreateParams params = build(true);

        assertTrue(params.tools().isPresent(), "the native tools parameter is populated");
        assertTrue(params.tools().get().toString().contains(TOOL_NAME),
                "and carries the announced definition");
        assertTrue(!systemText(params).contains(TOOL_NAME),
                "the definition is not also system text");
        assertEquals(0, countInTurns(params, TOOL_NAME),
                "and not also a rendered turn - the client's own encoder suppresses the inline"
                        + " form because the same client sweeps the blocks into the parameter");
    }

    @Test
    void aConversationThatCachesItsTurnsCarriesABreakpointOnItsFirstAndItsLastTurn_theLastMovingForward() {
        // A growing conversation is cheap only when a stored prefix ends at its end on every
        // request: each request then reads what the previous one sent
        for (int length : List.of(3, 6)) {
            MessageCreateParams params = build(true, conversation -> {
                conversation.setCacheTurns(true);
                addTurns(conversation, length);
            });
            assertTrue(lastSystemBlockIsCached(params), "the system prefix keeps its breakpoint");
            assertEquals(List.of(ASSISTANT_TURN, "CACHE-TURN-" + (length - 1) + ";"), breakpointTurns(params),
                    "the original request and the end of the conversation, and no turn between them (" + length + " turns added)");
        }
    }

    @Test
    void aConversationThatDoesNotCacheItsTurnsCarriesABreakpointOnlyWhereAMessageAsksByName() {
        assertEquals(List.of(), breakpointTurns(build(true, conversation -> addTurns(conversation, 4))),
                "every turn is UNLIKELY, so no stored prefix ends on one");
        MessageCreateParams params = build(true, conversation -> {
            addTurns(conversation, 4);
            turn(conversation, 1).setCacheability(Cacheability.ALWAYS);
            turn(conversation, 2).setCacheability(Cacheability.PREFERRED);
        });
        assertEquals(List.of("CACHE-TURN-1;", "CACHE-TURN-2;"), breakpointTurns(params),
                "a message that states ALWAYS or PREFERRED is heard in a conversation that caches nothing else");
    }

    @Test
    void everyAlwaysTurnCarriesABreakpoint_whateverTheirNumber() {
        // How many breakpoints a request may carry is Anthropic's rule; a request that
        // carries more is refused by Anthropic, and the caller sees that refusal. The
        // client drops none of what the caller asked for by name
        MessageCreateParams params = build(true, conversation -> {
            conversation.setCacheTurns(true);
            addTurns(conversation, 6);
            for (int i = 0; i < 5; i++) {
                turn(conversation, i).setCacheability(Cacheability.ALWAYS);
            }
        });
        assertEquals(List.of("CACHE-TURN-0;", "CACHE-TURN-1;", "CACHE-TURN-2;", "CACHE-TURN-3;", "CACHE-TURN-4;"), breakpointTurns(params),
                "all five ALWAYS turns, and with the allowance spent neither the end nor the first turn");
    }

    @Test
    void whatIsLeftAfterTheEndGoesToPreferredTurnsFromTheLatestBackwards_thenToTheFirstTurn() {
        java.util.function.Consumer<ConversationContext> threePreferred = conversation -> {
            conversation.setCacheTurns(true);
            addTurns(conversation, 6);
            for (int i = 0; i < 3; i++) {
                turn(conversation, i).setCacheability(Cacheability.PREFERRED);
            }
        };
        assertEquals(List.of("CACHE-TURN-1;", "CACHE-TURN-2;", "CACHE-TURN-5;"), breakpointTurns(build(true, threePreferred)),
                "three are left beside the system prefix: the end, then the two latest PREFERRED turns");
        assertEquals(List.of("CACHE-TURN-0;", "CACHE-TURN-1;", "CACHE-TURN-2;", "CACHE-TURN-5;"), breakpointTurns(build(false, threePreferred)),
                "with the system prefix uncached a fourth is left, and it goes to the third PREFERRED turn before the first turn");
        MessageCreateParams onePreferred = build(true, conversation -> {
            conversation.setCacheTurns(true);
            addTurns(conversation, 6);
            turn(conversation, 2).setCacheability(Cacheability.PREFERRED);
        });
        assertEquals(List.of(ASSISTANT_TURN, "CACHE-TURN-2;", "CACHE-TURN-5;"), breakpointTurns(onePreferred),
                "the first turn takes what the PREFERRED turns leave");
    }

    @Test
    void noBreakpointSitsOnOrAfterANeverTurn() {
        MessageCreateParams params = build(true, conversation -> {
            conversation.setCacheTurns(true);
            addTurns(conversation, 6);
            turn(conversation, 2).setCacheability(Cacheability.NEVER);
            turn(conversation, 4).setCacheability(Cacheability.ALWAYS);
        });
        assertEquals(List.of(ASSISTANT_TURN, "CACHE-TURN-1;"), breakpointTurns(params),
                "a prefix ending on or after the NEVER turn would contain it, so the end is the turn"
                        + " before it and the ALWAYS turn after it goes unstored");
    }

    @Test
    void anUnlikelyLastTurnLeavesTheEndOnTheTurnBeforeIt() {
        MessageCreateParams params = build(true, conversation -> {
            conversation.setCacheTurns(true);
            addTurns(conversation, 4);
            turn(conversation, 3).setCacheability(Cacheability.UNLIKELY);
        });
        assertEquals(List.of(ASSISTANT_TURN, "CACHE-TURN-2;"), breakpointTurns(params),
                "an UNLIKELY turn rides inside a stored prefix and never ends one");
    }

    @Test
    void theBreakpointSitsOnTheLastBlockOfItsTurn() {
        MessageCreateParams params = build(true, conversation -> {
            conversation.setCacheTurns(true);
            OutgoingMessage<String> twoBlocks = message("user", "FIRST-BLOCK");
            twoBlocks.addText("LAST-BLOCK");
            conversation.getMessages().add(twoBlocks);
        });
        List<ContentBlockParam> blocks = params.messages().getLast().content().asBlockParams();
        assertTrue(blocks.getFirst().toString().contains("FIRST-BLOCK") && blocks.getFirst().asText().cacheControl().isEmpty(),
                "the first block carries no breakpoint: a prefix ending there would leave the rest of the turn outside it");
        assertTrue(blocks.getLast().asText().cacheControl().isPresent(), "the last block carries it, so the stored prefix holds the whole turn");
    }

    @Test
    void aTurnOfToolResultsOnlyCanEndAStoredPrefix() {
        // A results turn with no text of its own: no response handler, so no instructions
        // are appended to it. Such a turn has no text block for a breakpoint to sit on
        MessageCreateParams params = build(true, conversation -> {
            IncomingMessage<String> call = new IncomingMessage<>(StringResponseHandler.instance);
            call.setContentBlocks(List.of(new ai.redouble.nucleo.harness.conversation.ContentBlocks.ToolUseBlock("toolu_1", TOOL_NAME, "{}")));
            conversation.getMessages().add(call);
            OutgoingMessage<String> results = new OutgoingMessage<>(null);
            results.setRole("user");
            results.addToolResult("toolu_1", "RESULT-MARKER", false);
            results.setCacheability(Cacheability.ALWAYS);
            conversation.getMessages().add(results);
            conversation.getMessages().add(message("user", "CACHE-TURN-0;"));
        });
        List<MessageParam> turns = params.messages();
        List<ContentBlockParam> blocks = turns.get(indexOfTurnContaining(turns, "RESULT-MARKER")).content().asBlockParams();
        assertEquals(1, blocks.size(), "the turn is its tool result and nothing else");
        assertTrue(blocks.getLast().isToolResult());
        assertTrue(blocks.getLast().asToolResult().cacheControl().isPresent(), "and the tool result carries the breakpoint");
    }

    /** Adds {@code count} user turns, each stating nothing about the cache, marked CACHE-TURN-0; onwards. */
    private static void addTurns(ConversationContext conversation, int count) {
        for (int i = 0; i < count; i++) {
            conversation.getMessages().add(message("user", "CACHE-TURN-" + i + ";"));
        }
    }

    /** The i-th turn {@link #addTurns} added. */
    private static ai.redouble.nucleo.harness.conversation.Message turn(ConversationContext conversation, int i) {
        for (ai.redouble.nucleo.harness.conversation.Message message : conversation.getMessages()) {
            if (message.getRawContent().contains("CACHE-TURN-" + i + ";")) {
                return message;
            }
        }
        throw new AssertionError("no added turn " + i);
    }

    /** The marker of every turn that carries a cache breakpoint, in wire order. */
    private static List<String> breakpointTurns(MessageCreateParams params) {
        List<String> found = new ArrayList<>();
        for (MessageParam message : params.messages()) {
            String rendered = message.content().toString();
            if (!rendered.contains("CacheControlEphemeral")) {
                continue;
            }
            if (rendered.contains(ASSISTANT_TURN)) {
                found.add(ASSISTANT_TURN);
            }
            else if (rendered.contains(USER_TURN)) {
                found.add(USER_TURN);
            }
            else {
                int at = rendered.indexOf("CACHE-TURN-");
                found.add(rendered.substring(at, rendered.indexOf(';', at) + 1));
            }
        }
        return found;
    }

    /** Same canonical conversation as the other two shape tests. */
    private MessageCreateParams build(boolean cacheObjective) {
        return build(cacheObjective, conversation -> { });
    }

    private MessageCreateParams build(boolean cacheObjective, java.util.function.Consumer<ConversationContext> extraTurns) {
        // The direct client's wire shape, on a direct entry from this artifact's own fragment
        return build(Models.spec("claude-haiku-4-5-direct"), cacheObjective, extraTurns);
    }

    /** An entry like the shipped ones in every respect a request reads, with its own count of cache breakpoints. */
    private static ModelSpec entryWithCacheBreakpoints(int cacheBreakpoints) {
        ModelSpec shipped = Models.spec("claude-haiku-4-5-direct");
        AnthropicModelSpec spec = new AnthropicModelSpec();
        spec.setId("claude-test-" + cacheBreakpoints + "-breakpoints");
        spec.setIdentity(shipped.getIdentity());
        spec.setProviderKey(shipped.getProviderKey());
        spec.setWireModelId(shipped.getWireModelId());
        spec.setMaxContextTokens(shipped.getMaxContextTokens());
        spec.setMaxOutputTokens(shipped.getMaxOutputTokens());
        spec.setGrade(shipped.getGrade());
        spec.setCacheBreakpoints(cacheBreakpoints);
        return spec;
    }

    @Test
    void theNumberOfBreakpointsIsTheCatalogEntrys() {
        java.util.function.Consumer<ConversationContext> growing = conversation -> {
            conversation.setCacheTurns(true);
            addTurns(conversation, 6);
        };
        MessageCreateParams two = build(entryWithCacheBreakpoints(2), true, growing);
        assertTrue(two.system().orElseThrow().asTextBlockParams().getLast().cacheControl().isPresent(), "the system content takes the first");
        assertEquals(List.of("CACHE-TURN-5;"), breakpointTurns(two), "and the end the second; nothing is left for the first turn");
        MessageCreateParams none = build(entryWithCacheBreakpoints(0), true, growing);
        assertTrue(none.system().orElseThrow().asTextBlockParams().getLast().cacheControl().isEmpty(), "an entry that takes none is sent none");
        assertEquals(List.of(), breakpointTurns(none));
    }

    private MessageCreateParams build(ModelSpec model, boolean cacheObjective, java.util.function.Consumer<ConversationContext> extraTurns) {
        ConversationContext conversation = TestModels.conversation(model);
        conversation.setDepth(Depth.IMMEDIATE);
        conversation.setOutputDeclaration(OutputDeclaration.of(TEST_DEFAULT));
        conversation.setCacheMainObjective(cacheObjective);
        conversation.putMainObjective("task", OBJECTIVE);
        conversation.addTool(new ai.redouble.nucleo.harness.conversation.ContentBlocks.ToolDefinitionBlock(
                TOOL_NAME, "finds things", "{\"type\":\"object\",\"properties\":{},\"required\":[]}"));
        conversation.getMessages().add(message("assistant", ASSISTANT_TURN));
        conversation.getMessages().add(message("user", USER_TURN));
        extraTurns.accept(conversation);
        BuilderOnlyClient client = new BuilderOnlyClient();
        client.setModel(model);
        PreparedConversation prepared = LlmTestDoors.prepare(client, conversation);
        return client.buildMessageCreateParams(conversation, prepared);
    }

    private static int indexOfTurnContaining(List<MessageParam> turns, String marker) {
        for (int i = 0; i < turns.size(); i++) {
            if (turns.get(i).content().toString().contains(marker)) {
                return i;
            }
        }
        throw new AssertionError("no turn carries " + marker);
    }

    private static OutgoingMessage<String> message(String role, String text) {
        OutgoingMessage<String> message = new OutgoingMessage<>(StringResponseHandler.instance);
        message.setRole(role);
        message.addText(text);
        return message;
    }

    private static String systemText(MessageCreateParams params) {
        return params.system().map(Object::toString).orElse("");
    }

    private static int countInTurns(MessageCreateParams params, String marker) {
        int found = 0;
        for (MessageParam message : params.messages()) {
            if (message.content().toString().contains(marker)) {
                found++;
            }
        }
        return found;
    }

    private static MessageParam.Role roleOfTurnContaining(MessageCreateParams params, String marker) {
        for (MessageParam message : params.messages()) {
            if (message.content().toString().contains(marker)) {
                return message.role();
            }
        }
        throw new AssertionError("no turn carries " + marker);
    }

    /** Reaches the request builder without the credential read the public constructor performs. */
    private static final class BuilderOnlyClient extends AnthropicSDKClient {
        BuilderOnlyClient() {
            super(true);
        }
    }
}
