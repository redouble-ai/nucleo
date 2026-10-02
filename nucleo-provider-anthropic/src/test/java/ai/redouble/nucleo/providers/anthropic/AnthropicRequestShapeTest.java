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
    void cacheBreakpointsAreCappedAtFour_spentInOrder() {
        // Anthropic allows 4 breakpoints per request. With the system prefix taking the
        // first, only the three EARLIEST cache-flagged turns get one; the rest are
        // silently unmarked rather than overflowing the request.
        MessageCreateParams params = build(true, conversation -> {
            for (int i = 0; i < 6; i++) {
                OutgoingMessage<String> turn = message("user", "CACHE-TURN-" + i);
                turn.setCache(true);
                conversation.getMessages().add(turn);
            }
        });
        assertTrue(lastSystemBlockIsCached(params), "the system prefix keeps the first breakpoint");
        List<MessageParam> turns = params.messages();
        for (int i = 0; i < 6; i++) {
            boolean cached = turns.get(indexOfTurnContaining(turns, "CACHE-TURN-" + i))
                    .toString().contains("CacheControlEphemeral");
            if (i < 3) {
                assertTrue(cached, "flagged turn " + i + " takes one of the remaining breakpoints, in order");
            }
            else {
                assertTrue(!cached, "flagged turn " + i + " is beyond the provider's limit and stays unmarked");
            }
        }
    }

    /** Same canonical conversation as the other two shape tests. */
    private MessageCreateParams build(boolean cacheObjective) {
        return build(cacheObjective, conversation -> { });
    }

    private MessageCreateParams build(boolean cacheObjective, java.util.function.Consumer<ConversationContext> extraTurns) {
        // The direct client's wire shape, on a direct entry from this artifact's own fragment
        ModelSpec model = Models.spec("claude-haiku-4-5-direct");
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
