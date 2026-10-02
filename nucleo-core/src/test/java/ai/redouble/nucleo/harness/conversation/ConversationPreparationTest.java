/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the channel contract of {@link ConversationContext#prepareMessagesForLLM}: the main
 * objective is the conversation's only system content and comes back as
 * {@link PreparedConversation#systemText()}, never as a turn; turns are user or assistant
 * and nothing else. This is the single place roles become wire roles, so the contract is
 * pinned here once rather than once per client.
 *
 * <p>Also pins the objective-map contract: the objective is a map of named slots rendered
 * in insertion order, the schema-notation legend follows the response contract (a handler
 * declaring {@code usesSchemaNotation()}) and never a plain-prose conversation, declared
 * tools convert at first render (head) or arrive as stream announcements after it, and the
 * head freezes at first render - divergent mutations are loudly logged no-ops.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-23)
 */
public class ConversationPreparationTest {

    private static final String OBJECTIVE = "OBJECTIVE-MARKER extract the port labels";
    private static final String USER_TURN = "USER-MARKER Ringports changed to 5 and 7.";
    private static final String ASSISTANT_TURN = "ASSISTANT-MARKER previous answer";

    @Test
    void objectiveBecomesSystemText_andIsNotATurn() {
        PreparedConversation prepared = prepare(conversation());

        assertTrue(prepared.systemText().contains(OBJECTIVE),
                "the objective is the system content");
        for (ProcessedMessageData turn : prepared.turns()) {
            assertFalse(textOf(turn).contains(OBJECTIVE),
                    "and appears in no turn - flattening it into the turn list under a made-up"
                            + " role is what let a client ship it twice");
        }
    }

    @Test
    void turnRolesSurviveTheMapping() {
        PreparedConversation prepared = prepare(conversation());

        assertEquals(2, prepared.turns().size(), "two messages, two turns");
        assertEquals(TurnRole.ASSISTANT, prepared.turns().get(0).role());
        assertEquals(TurnRole.USER, prepared.turns().get(1).role());
    }

    @Test
    void cacheMainObjectiveRidesThePreparedConversation() {
        ConversationContext conversation = conversation();
        conversation.setCacheMainObjective(false);

        assertFalse(prepare(conversation).cacheSystem(),
                "the flag decides whether the system content is cached, and it must survive preparation");
        conversation.setCacheMainObjective(true);
        assertTrue(prepare(conversation).cacheSystem());
    }

    @Test
    void theLegendFollowsTheContract_intoTheHead() {
        // A bare conversation built by a doer around a plain LLMCall - no thinker, no
        // composition - ships the @-notation legend the moment its ask carries a
        // structured response contract: the contract is the notation's only producer,
        // and a model that never had it explained echoes the descriptor shape instead
        // of answering (observed live on Nova Micro).
        ConversationContext conversation = TestModels.conversation(TestModels.small());
        conversation.getMessages().add(structuredAsk(USER_TURN));
        PreparedConversation prepared = prepare(conversation);

        assertTrue(prepared.hasSystemText(), "the legend alone is still system content");
        assertTrue(prepared.systemText().contains("keys beginning with @"),
                "the legend is in the system text of a conversation nobody composed");
    }

    @Test
    void plainProseConversation_carriesNoLegend() {
        ConversationContext conversation = TestModels.conversation(TestModels.small());
        conversation.getMessages().add(message("user", USER_TURN));

        assertFalse(prepare(conversation).systemText().contains("keys beginning with @"),
                "no @-notation in the request, no legend explaining it - the legend is a"
                        + " property of the contract, not of the conversation");
    }

    @Test
    void notationAppearingAfterTheFreeze_bringsTheLegendAsAStreamMessage() {
        ConversationContext conversation = TestModels.conversation(TestModels.small());
        conversation.getMessages().add(message("user", USER_TURN));
        prepare(conversation);
        conversation.getMessages().add(structuredAsk("now answer structured"));
        prepare(conversation);

        assertEquals(3, conversation.getMessages().size(),
                "the legend arrives in the stream at the point the notation first appeared -"
                        + " the frozen head did not move");
        OutgoingMessage<?> last = (OutgoingMessage<?>) conversation.getMessages().get(2);
        assertTrue(textOf(last.getContentBlocks()).contains("keys beginning with @"));
        prepare(conversation);
        assertEquals(3, conversation.getMessages().size(),
                "presence is derived from content, so the reconcile is idempotent");
    }

    @Test
    void putMainObjective_isASlot_lastWinsInPlace() {
        ConversationContext conversation = TestModels.conversation(TestModels.small());
        conversation.putMainObjective("task", "FIRST-VALUE");
        conversation.putMainObjective("extra", "EXTRA-MARKER");
        conversation.putMainObjective("task", OBJECTIVE);
        conversation.getMessages().add(message("user", USER_TURN));
        String systemText = prepare(conversation).systemText();

        assertFalse(systemText.contains("FIRST-VALUE"), "a re-put replaces the slot's value");
        assertTrue(systemText.indexOf(OBJECTIVE) < systemText.indexOf("EXTRA-MARKER"),
                "and keeps the slot's original position - render order is insertion order,"
                        + " so a refresh never reorders the prefix");
    }

    @Test
    void declaredTools_convertIntoTheHeadAtFirstRender() {
        ConversationContext conversation = TestModels.conversation(TestModels.small());
        ContentBlocks.ToolDefinitionBlock tool =
                new ContentBlocks.ToolDefinitionBlock("probe_tool", "finds things", "{\"type\":\"object\"}");
        conversation.addTool(tool);
        conversation.getMessages().add(message("user", USER_TURN));
        assertTrue(conversation.announcedTools().isEmpty(),
                "declaring converts nothing - pre-render churn is free");

        PreparedConversation prepared = prepare(conversation);

        assertEquals(List.of(tool), prepared.toolDefinitions(),
                "at first render the palette lands in the head and travels as the typed channel");
        assertEquals(tool, conversation.announcedTools().get("probe_tool"),
                "the announced set sees the head palette");
        assertTrue(prepared.systemText().contains("Available tools:"),
                "the tool guidance appears exactly when tools do");
        assertEquals(1, conversation.getMessages().size(),
                "the initial palette is stable head content, not a message");
    }

    @Test
    void toolLessConversation_getsNoToolGuidance() {
        ConversationContext conversation = TestModels.conversation(TestModels.small());
        conversation.getMessages().add(message("user", USER_TURN));

        assertFalse(prepare(conversation).systemText().contains("Available tools:"),
                "no tools, no dangling header - an unfulfilled announcement primes weak models");
    }

    @Test
    void declaredTools_afterTheFreezeArriveAsAStreamAnnouncement() {
        ConversationContext conversation = TestModels.conversation(TestModels.small());
        ContentBlocks.ToolDefinitionBlock first =
                new ContentBlocks.ToolDefinitionBlock("first_tool", "initial", "{}");
        ContentBlocks.ToolDefinitionBlock second =
                new ContentBlocks.ToolDefinitionBlock("second_tool", "admitted later", "{}");
        conversation.addTool(first);
        conversation.getMessages().add(message("user", USER_TURN));
        prepare(conversation);
        conversation.addTool(second);
        PreparedConversation prepared = prepare(conversation);

        assertEquals(List.of(first), prepared.toolDefinitions(),
                "the frozen head still carries only the first render's palette");
        assertEquals(2, prepared.allToolDefinitions().size(),
                "the admitted tool reaches the model through the turns");
        assertEquals(2, conversation.getMessages().size(),
                "the admission is one appended message - the prefix ahead of it did not move");
        OutgoingMessage<?> last = (OutgoingMessage<?>) conversation.getMessages().get(1);
        assertTrue(textOf(last.getContentBlocks()).contains("Additional tools now available"),
                "an admission is introduced as an addition, not presented as the initial palette");
        assertEquals(List.of(second),
                last.getContentBlocks().stream().filter(b -> b instanceof ContentBlocks.ToolDefinitionBlock).toList(),
                "the appended message carries only the newly admitted tool");
        prepare(conversation);
        assertEquals(2, conversation.getMessages().size(),
                "reconciliation is idempotent - a render with nothing pending appends nothing");
    }

    @Test
    void declarationsAreAddOnly_aDriftedDefinitionIsDiscarded() {
        ConversationContext conversation = TestModels.conversation(TestModels.small());
        conversation.addTool(new ContentBlocks.ToolDefinitionBlock("tool", "original", "{}"));
        conversation.addTool(new ContentBlocks.ToolDefinitionBlock("tool", "drifted", "{}"));
        conversation.getMessages().add(message("user", USER_TURN));

        assertEquals("original", prepare(conversation).toolDefinitions().get(0).description(),
                "a name already declared is never redefined - definitions are immutable");
    }

    @Test
    void postFreeze_divergentPutIsALoudNoOp() {
        ConversationContext conversation = conversation();
        String before = prepare(conversation).systemText();
        conversation.putMainObjective("task", "SMUGGLED-CHANGE");
        conversation.putMainObjective("brand-new-key", "SMUGGLED-ADDITION");

        assertEquals(before, prepare(conversation).systemText(),
                "the frozen head is bit-identical after the refused puts - mutating it would"
                        + " silently invalidate the cached prefix");
    }

    @Test
    void postFreeze_renderedEqualRePutIsSilent() {
        ConversationContext conversation = conversation();
        prepare(conversation);
        conversation.putMainObjective("task", OBJECTIVE);

        assertTrue(prepare(conversation).systemText().contains(OBJECTIVE),
                "the sprinkle put - same slot, same rendered value - stays free forever");
    }

    @Test
    void postFreeze_nukeIsALoudNoOp() {
        ConversationContext conversation = conversation();
        String before = prepare(conversation).systemText();
        conversation.nukeMainObjective();

        assertEquals(before, prepare(conversation).systemText());
    }

    @Test
    void preFreeze_nukeClearsTheMap_derivedCompanionsReDerive() {
        ConversationContext conversation = TestModels.conversation(TestModels.small());
        conversation.putMainObjective("task", OBJECTIVE);
        conversation.nukeMainObjective();
        conversation.getMessages().add(message("user", USER_TURN));

        assertFalse(prepare(conversation).hasSystemText(),
                "nuke means nuke - no slot survives and nothing authored re-seeds itself");

        ConversationContext structured = TestModels.conversation(TestModels.small());
        structured.putMainObjective("task", OBJECTIVE);
        structured.nukeMainObjective();
        structured.getMessages().add(structuredAsk(USER_TURN));
        String systemText = prepare(structured).systemText();
        assertFalse(systemText.contains(OBJECTIVE), "the authored task stays nuked");
        assertTrue(systemText.contains("keys beginning with @"),
                "but the legend is not stored state - it derives from the contract the"
                        + " conversation still renders, the same way tool guidance derives"
                        + " from tools still declared");
    }

    @Test
    void snapshotRoundTrip_keysDeclarationsAndTheFreezeResetSurvive() {
        ConversationContext original = conversation();
        original.addTool(new ContentBlocks.ToolDefinitionBlock("probe_tool", "finds things", "{}"));
        String sent = prepare(original).systemText();

        ConversationContext restored = original.toSnapshot().toConversation(StringResponseHandler.instance);

        assertEquals(original.getMainObjective().keySet(), restored.getMainObjective().keySet(),
                "the objective map replays with its keys, in order");
        assertTrue(restored.announcedTools().containsKey("probe_tool"),
                "the head palette survives persistence");
        restored.putMainObjective("task", "REVISED-AFTER-RESTORE");
        assertTrue(prepare(restored).systemText().contains("REVISED-AFTER-RESTORE"),
                "a restored instance has not rendered, so composition is legal again - there"
                        + " is no warm provider cache to lose");
        assertTrue(sent.contains(OBJECTIVE), "and the original send carried the original task");
    }

    @Test
    void legacySnapshot_anonymousBlocksReplayUnderGeneratedKeys() {
        // A snapshot written before the objective became a keyed map carries an anonymous
        // block list; text replays under generated keys and head tools become declarations,
        // so the first render of the restored instance rebuilds the same head.
        ConversationPersistenceSnapshot snapshot = new ConversationPersistenceSnapshot();
        snapshot.setConversationId("legacy-conv");
        snapshot.setMainObjectiveBlocks(List.of(
                ContentBlockSnapshot.fromBlock(new ContentBlocks.TextBlock(OBJECTIVE)),
                ContentBlockSnapshot.fromBlock(new ContentBlocks.ToolDefinitionBlock("legacy_tool", "from before", "{}"))));

        ConversationContext restored = snapshot.toConversation(StringResponseHandler.instance);
        restored.getMessages().add(message("user", USER_TURN));
        PreparedConversation prepared = prepare(restored);

        assertTrue(prepared.systemText().contains(OBJECTIVE), "the anonymous text is back in the head");
        assertEquals("legacy_tool", prepared.toolDefinitions().get(0).name(),
                "the head tool declared its way back into the head");
    }

    @Test
    void systemRoleMessageIsRefused() {
        // System content goes through putMainObjective, never through a message. A message
        // claiming the system role is a corrupted conversation, and silently relabelling it
        // (the old behavior on two of three providers) is how instruction text ends up
        // indistinguishable from user input.
        ConversationContext conversation = conversation();
        conversation.getMessages().add(message("system", "smuggled instruction"));

        assertThrows(UncorrectableRuntimeLLMException.class, () -> prepare(conversation));
    }

    private static ConversationContext conversation() {
        ConversationContext conversation = TestModels.conversation(TestModels.small());
        conversation.putMainObjective("task", OBJECTIVE);
        conversation.getMessages().add(message("assistant", ASSISTANT_TURN));
        conversation.getMessages().add(message("user", USER_TURN));
        return conversation;
    }

    private static PreparedConversation prepare(ConversationContext conversation) {
        return conversation.prepareMessagesForLLM(new GenericContentFormatter(), false);
    }

    private static OutgoingMessage<String> message(String role, String text) {
        OutgoingMessage<String> message = new OutgoingMessage<>(StringResponseHandler.instance);
        message.setRole(role);
        message.addText(text);
        return message;
    }

    /** An ask whose handler renders an @-notation contract - the legend's trigger. */
    private static OutgoingMessage<ProbeAnswer> structuredAsk(String text) {
        OutgoingMessage<ProbeAnswer> message = new OutgoingMessage<>(new PojoResponseHandler<>(ProbeAnswer.class));
        message.setRole("user");
        message.addText(text);
        return message;
    }

    public static class ProbeAnswer {
        private String answer;
        public String getAnswer() {
            return answer;
        }
        public void setAnswer(String answer) {
            this.answer = answer;
        }
    }

    private static String textOf(ProcessedMessageData turn) {
        return textOf(turn.contentBlocks());
    }

    private static String textOf(java.util.List<ContentBlocks.ContentBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlocks.ContentBlock block : blocks) {
            if (block instanceof ContentBlocks.TextBlock tb) {
                sb.append(tb.text());
            }
        }
        return sb.toString();
    }
}
