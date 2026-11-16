/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.registry.*;
import ai.redouble.nucleo.tools.thinking.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins where a reply format is stated in a rendered conversation. A format is defined once,
 * in the system content, under its name, however many turns ask for it; every turn that asks
 * ends with one line naming it; a plain-text ask defines nothing. The definition is written at
 * each render for the model the render is for and is stored nowhere: not in the head, not in a
 * message, not in a saved conversation. The same conversation rendered for another kind of
 * model carries that model's definition, and rendered for the first kind again carries the
 * first text again. A format's name is its identity: the first handler that carries a name
 * writes its definition, once per requirements. A second format name that appears after the
 * first render is defined in the system content beside the first.
 *
 * @author Andrey Santrosyan
 * @since 0.1.4 (2026-11-15)
 */
public class ReplyFormatPlacementTest {

    private static final String DEFINITION_OF_PROBE = "Reply format ProbeAnswer:";
    private static final String LINE_OF_PROBE = "\n\nReply in the JSON format ProbeAnswer, defined above.";

    @Test
    void aFormatIsDefinedOnceInTheSystemContent_howeverManyTurnsAskForIt() {
        ConversationContext conversation = TestModels.conversation(TestModels.small());
        conversation.putMainObjective("task", "extract the port labels");
        for (int i = 0; i < 5; i++) {
            conversation.getMessages().add(structuredAsk("question " + i));
        }
        PreparedConversation prepared = prepare(conversation);

        assertEquals(1, occurrences(prepared.systemText(), DEFINITION_OF_PROBE), "five asks, one definition: " + prepared.systemText());
        assertEquals(1, occurrences(prepared.systemText(), "\"@type\" : \"ProbeAnswer\""),
                "the definition's schema is rooted at the format's name, once");
        assertEquals(5, prepared.turns().size());
        for (ProcessedMessageData turn : prepared.turns()) {
            String text = textOf(turn.contentBlocks());
            assertTrue(text.endsWith(LINE_OF_PROBE), "every asking turn ends with the line naming the format: " + text);
            assertFalse(text.contains("@fields"), "and carries no schema: " + text);
            assertFalse(text.contains(DEFINITION_OF_PROBE), "and no definition: " + text);
        }
    }

    @Test
    void theDefinitionFollowsTheLegend_andPrecedesTheTools() {
        ConversationContext conversation = TestModels.conversation(TestModels.small());
        conversation.putMainObjective("task", "extract the port labels");
        conversation.addTool(new ContentBlocks.ToolDefinitionBlock("probe_tool", "finds things", "{\"type\":\"object\"}"));
        conversation.getMessages().add(structuredAsk("question"));
        String system = prepare(conversation).systemText();

        int task = system.indexOf("extract the port labels");
        int legend = system.indexOf("keys beginning with @");
        int definition = system.indexOf(DEFINITION_OF_PROBE);
        int tools = system.indexOf("Available tools:");
        assertTrue(task >= 0 && task < legend && legend < definition && definition < tools,
                "task, the legend that explains the notation, the format written in it, then the tools: " + system);
    }

    @Test
    void aFormatFirstAskedForAfterAProseRender_isDefinedInTheSystemContent_beforeTheTools() {
        ConversationContext conversation = TestModels.conversation(TestModels.small());
        conversation.addTool(new ContentBlocks.ToolDefinitionBlock("probe_tool", "finds things", "{\"type\":\"object\"}"));
        conversation.getMessages().add(prose("tell me about it"));
        assertFalse(prepare(conversation).systemText().contains("Reply format"), "a plain-text ask defines no format");
        conversation.getMessages().add(structuredAsk("now answer structured"));
        String system = prepare(conversation).systemText();

        assertFalse(system.contains("keys beginning with @"), "the legend arrived after the freeze, so it is in the turn");
        int definition = system.indexOf(DEFINITION_OF_PROBE);
        assertTrue(definition >= 0 && definition < system.indexOf("Available tools:"),
                "with no legend in the head the definition goes before the tool guidance: " + system);
    }

    @Test
    void aPlainTextAsk_definesNoFormat_andKeepsItsOwnLine() {
        ConversationContext conversation = TestModels.conversation(TestModels.small());
        conversation.getMessages().add(prose("tell me about it"));
        PreparedConversation prepared = prepare(conversation);

        assertFalse(prepared.systemText().contains("Reply format"));
        assertTrue(textOf(prepared.turns().get(0).contentBlocks()).endsWith("\nResponse must be a string"));
    }

    @Test
    void aRenderWritesTheDefinitionForItsModel_andStoresNothing() {
        ConversationContext conversation = TestModels.conversation(TestModels.small());
        conversation.putMainObjective("task", "decide");
        conversation.getMessages().add(thinkerAsk("first"));
        conversation.getMessages().add(thinkerAsk("second"));
        prepare(conversation);
        List<ContentBlocks.ContentBlock> headAfterFirstRender = new ArrayList<>(conversation.getMainObjectiveBlocks());
        List<List<ContentBlocks.ContentBlock>> turnsAfterFirstRender = storedBlocks(conversation);
        GenericContentFormatter formatter = new GenericContentFormatter();
        String withoutThinking = conversation.render(formatter, false).systemText();
        String withThinking = conversation.render(formatter, true).systemText();
        String withoutThinkingAgain = conversation.render(formatter, false).systemText();

        String header = "Reply format ThinkingResponse_of_ProbeAnswer:";
        assertEquals(1, occurrences(withoutThinking, header));
        assertEquals(1, occurrences(withThinking, header), "a request never holds two definitions of one name");
        assertTrue(withoutThinking.contains("\"reasoning\""), "a model with no native thinking is asked for its reasoning");
        assertFalse(withThinking.contains("\"reasoning\""), "a model whose thinking is native is not");
        assertEquals(withoutThinking, withoutThinkingAgain,
                "rendered for the first kind of model again, the system content is what it was, so that model's stored prefix still matches");
        assertEquals(withoutThinking, conversation.prepareMessagesForLLM(formatter, false).systemText(),
                "the render that commits and the pure render write the same text");
        assertEquals(headAfterFirstRender, conversation.getMainObjectiveBlocks(), "no render put a definition in the stored head");
        assertEquals(turnsAfterFirstRender, storedBlocks(conversation), "and none put one in a message");
        for (ContentBlocks.ContentBlock block : conversation.getMainObjectiveBlocks()) {
            assertFalse(block instanceof ContentBlocks.TextBlock(String text) && text.contains("Reply format"),
                    "the stored head holds no definition: " + block);
        }
    }

    @Test
    void aSecondFormatNameAfterTheFirstRender_isDefinedInTheSystemContentBesideTheFirst() {
        ConversationContext conversation = TestModels.conversation(TestModels.small());
        conversation.getMessages().add(structuredAsk("first"));
        prepare(conversation);
        OutgoingMessage<OtherAnswer> other = new OutgoingMessage<>(new PojoResponseHandler<>(OtherAnswer.class));
        other.setRole("user");
        other.addText("second, in another format");
        conversation.getMessages().add(other);
        conversation.getMessages().add(structuredAsk("third, in the first format again"));
        PreparedConversation prepared = prepare(conversation);

        String system = prepared.systemText();
        assertEquals(1, occurrences(system, DEFINITION_OF_PROBE));
        assertEquals(1, occurrences(system, "Reply format OtherAnswer:"));
        assertTrue(system.indexOf(DEFINITION_OF_PROBE) < system.indexOf("Reply format OtherAnswer:"), "in the order the names were first asked for");
        assertTrue(textOf(prepared.turns().get(1).contentBlocks()).endsWith("\n\nReply in the JSON format OtherAnswer, defined above."));
        for (List<ContentBlocks.ContentBlock> blocks : storedBlocks(conversation)) {
            assertFalse(textOf(blocks).contains("Reply format"), "a format named after the freeze is not written into a turn: " + textOf(blocks));
        }
    }

    @Test
    void aNameIsTheFormatsIdentity_theFirstHandlerDefinesIt_oncePerRequirements() {
        int[] written = new int[1];
        ConversationContext conversation = TestModels.conversation(TestModels.small());
        conversation.getMessages().add(countedAsk("first", "DEFINITION-OF-THE-FIRST", written));
        conversation.getMessages().add(countedAsk("second", "DEFINITION-OF-THE-SECOND", written));
        GenericContentFormatter formatter = new GenericContentFormatter();
        String system = conversation.render(formatter, false).systemText();

        assertEquals(1, occurrences(system, "Reply format Counted:"));
        assertTrue(system.contains("DEFINITION-OF-THE-FIRST"), "the first handler carrying the name writes its definition");
        assertFalse(system.contains("DEFINITION-OF-THE-SECOND"), "a later handler of that name adds none");
        conversation.render(formatter, false);
        conversation.prepareMessagesForLLM(formatter, false);
        assertEquals(1, written[0], "three renders for one kind of model write the definition once");
        conversation.render(formatter, true);
        conversation.render(formatter, true);
        assertEquals(2, written[0], "another kind of model has it written once more");
    }

    @Test
    void aSavedConversationHoldsNoDefinition_andARestoredOneDefinesTheFormatOfItsPendingAsk() {
        ConversationContext conversation = TestModels.conversation(TestModels.small());
        conversation.putMainObjective("task", "extract the port labels");
        conversation.getMessages().add(structuredAsk("pending question"));
        prepare(conversation);
        ConversationPersistenceSnapshot snapshot = conversation.toSnapshot();

        assertFalse(NucleoJsonSerializer.write(snapshot).contains("Reply format"), "nothing of a definition is persisted");
        PreparedConversation restored = prepare(snapshot.toConversation(new PojoResponseHandler<>(ConversationPreparationTest.ProbeAnswer.class)));
        assertEquals(1, occurrences(restored.systemText(), DEFINITION_OF_PROBE), "the restored conversation's pending ask has its format defined");
        assertTrue(textOf(restored.turns().get(restored.turns().size() - 1).contentBlocks()).endsWith(LINE_OF_PROBE));
    }

    /**
     * A thinker restores its conversation with a placeholder handler and then asks its
     * pending turn with its own, as here. Every turn the model had answered renders again
     * with the reply line it had, the first turn included, which holds nothing but that line;
     * a turn that was followed by another turn renders as its content alone.
     */
    @Test
    void aRestoredConversationRendersItsAnsweredTurnsAsTheLiveOneDid() {
        ConversationContext live = TestModels.conversation(TestModels.small());
        live.putMainObjective("task", "extract the port labels");
        live.getMessages().add(structuredAsk(""));
        live.getMessages().add(answer("{\"label\":\"A\"}"));
        OutgoingMessage<String> added = new OutgoingMessage<>();
        added.setRole("user");
        added.addText("a note added while the model worked");
        live.getMessages().add(added);
        live.getMessages().add(structuredAsk("second question"));
        live.getMessages().add(answer("{\"label\":\"B\"}"));
        live.getMessages().add(structuredAsk("pending question"));
        PreparedConversation before = prepare(live);

        ConversationContext restored = live.toSnapshot().toConversation(StringResponseHandler.instance);
        restored.setResponseHandler(new PojoResponseHandler<>(ConversationPreparationTest.ProbeAnswer.class));
        PreparedConversation after = prepare(restored);

        assertEquals(before.systemText(), after.systemText(), "the same system content, the format defined once");
        assertEquals(before.turns().size(), after.turns().size());
        for (int i = 0; i < before.turns().size(); i++) {
            assertEquals(textOf(before.turns().get(i).contentBlocks()), textOf(after.turns().get(i).contentBlocks()),
                    "turn " + i + " renders after the restore as it did before the save");
        }
        assertEquals(LINE_OF_PROBE, textOf(after.turns().get(0).contentBlocks()),
                "a first turn that holds only its ask is still that line, so the request still starts with a user turn");
        assertEquals("a note added while the model worked", textOf(after.turns().get(2).contentBlocks()),
                "a turn that was followed by another turn was never asked for a reply");
    }

    private static IncomingMessage<ConversationPreparationTest.ProbeAnswer> answer(String json) {
        IncomingMessage<ConversationPreparationTest.ProbeAnswer> message =
                new IncomingMessage<>(new PojoResponseHandler<>(ConversationPreparationTest.ProbeAnswer.class));
        message.setContentBlocks(List.of(new ContentBlocks.TextBlock(json)));
        return message;
    }

    public static class OtherAnswer {
        private String verdict;

        public String getVerdict() {
            return verdict;
        }

        public void setVerdict(String verdict) {
            this.verdict = verdict;
        }
    }

    private static PreparedConversation prepare(ConversationContext conversation) {
        return conversation.prepareMessagesForLLM(new GenericContentFormatter(), false);
    }

    private static OutgoingMessage<ConversationPreparationTest.ProbeAnswer> structuredAsk(String text) {
        OutgoingMessage<ConversationPreparationTest.ProbeAnswer> message =
                new OutgoingMessage<>(new PojoResponseHandler<>(ConversationPreparationTest.ProbeAnswer.class));
        message.setRole("user");
        message.addText(text);
        return message;
    }

    private static OutgoingMessage<String> prose(String text) {
        OutgoingMessage<String> message = new OutgoingMessage<>(StringResponseHandler.instance);
        message.setRole("user");
        message.addText(text);
        return message;
    }

    /** An ask in a thinker's envelope, whose definition depends on whether the model's thinking is native. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static OutgoingMessage<ThinkingResponse<ConversationPreparationTest.ProbeAnswer>> thinkerAsk(String text) {
        OutgoingMessage<ThinkingResponse<ConversationPreparationTest.ProbeAnswer>> message = new OutgoingMessage<>(
                new ThinkingResponseHandler<>(new ToolRegistry(),
                        new PojoResponseHandler<ThinkingResponse<ConversationPreparationTest.ProbeAnswer>>((Class) ThinkingResponse.class),
                        new PojoResponseHandler<>(ConversationPreparationTest.ProbeAnswer.class)));
        message.setRole("user");
        message.addText(text);
        return message;
    }

    /** An ask whose format is named {@code Counted} and whose handler counts the definitions it writes. */
    private static OutgoingMessage<String> countedAsk(String text, String definition, int[] written) {
        OutgoingMessage<String> message = new OutgoingMessage<>(new ResponseHandler<>() {
            @Override
            public Class<String> getResponseClass() {
                return String.class;
            }

            @Override
            public String parse(String rawContent) {
                return rawContent;
            }

            @Override
            public String write(String response) {
                return response;
            }

            @Override
            public List<String> getValidationErrors(String response) {
                return List.of();
            }

            @Override
            public PojoDefinition writeDefinition() {
                return StringResponseHandler.instance.writeDefinition();
            }

            @Override
            public String formatName() {
                return "Counted";
            }

            @Override
            public String formatDefinition(ResponseRequirements requirements) {
                written[0]++;
                return definition;
            }

            @Override
            public String replyInstructions(ResponseRequirements requirements) {
                return NAMED_REPLY_INSTRUCTIONS.formatted(formatName());
            }
        });
        message.setRole("user");
        message.addText(text);
        return message;
    }

    private static List<List<ContentBlocks.ContentBlock>> storedBlocks(ConversationContext conversation) {
        List<List<ContentBlocks.ContentBlock>> stored = new ArrayList<>();
        for (Message message : conversation.getMessages()) {
            stored.add(new ArrayList<>(((OutgoingMessage<?>) message).getContentBlocks()));
        }
        return stored;
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
            count++;
        }
        return count;
    }

    private static String textOf(List<ContentBlocks.ContentBlock> blocks) {
        StringBuilder text = new StringBuilder();
        for (ContentBlocks.ContentBlock block : blocks) {
            if (block instanceof ContentBlocks.TextBlock tb) {
                text.append(tb.text());
            }
        }
        return text.toString();
    }
}
