/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the restore-time message contracts of {@link ConversationPersistenceSnapshot}:
 * incoming messages persist their content blocks so tool_use ids survive; a tool_result
 * whose tool_use id appears NOWHERE in the transcript is dropped at restore (a provider
 * refuses the whole conversation over one orphan, so keeping it makes the stored
 * conversation permanently unanswerable); and a dehydrated message refuses to parse
 * until rehydrated with a handler. Also pins {@link OutgoingMessage#addText}'s refusal
 * of null/blank text - an empty block is not content.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-17)
 */
class ConversationRestoreContractTest {

    private static ConversationContext conversationWithToolExchange() {
        ConversationContext conversation = new ConversationContext();
        OutgoingMessage<String> question = new OutgoingMessage<>(StringResponseHandler.instance);
        question.setRole("user");
        question.addText("find the ports");
        conversation.addMessage(question);
        IncomingMessage<String> assistant = new IncomingMessage<>(StringResponseHandler.instance);
        assistant.setContentBlocks(List.of(
                new TextBlock("calling the tool"),
                new ToolUseBlock("toolu_matched", "search", "{\"q\":\"ports\"}")));
        conversation.addMessage(assistant);
        OutgoingMessage<String> matchedResult = new OutgoingMessage<>(StringResponseHandler.instance);
        matchedResult.setRole("user");
        matchedResult.addToolResult("toolu_matched", "{\"hits\":2}", false);
        conversation.addMessage(matchedResult);
        OutgoingMessage<String> orphanResult = new OutgoingMessage<>(StringResponseHandler.instance);
        orphanResult.setRole("user");
        orphanResult.addToolResult("toolu_orphan", "{\"hits\":0}", false);
        conversation.addMessage(orphanResult);
        return conversation;
    }

    @Test
    void incomingBlocksSurvivePersistence_soToolUseIdsExistForTheirResults() {
        ConversationContext restored = conversationWithToolExchange().toSnapshot()
                .toConversation(StringResponseHandler.instance);
        IncomingMessage<?> assistant = (IncomingMessage<?>) restored.getMessages().get(1);
        assertTrue(assistant.hasContentBlocks(), "the assistant turn keeps its blocks through persistence");
        assertTrue(assistant.getContentBlocks().stream().anyMatch(b -> b instanceof ToolUseBlock tu
                        && tu.toolUseId().equals("toolu_matched")),
                "the tool_use id survives for the tool_result that references it");
    }

    @Test
    void orphanToolResultIsDroppedAtRestore_matchedOnesSurvive() {
        ConversationContext restored = conversationWithToolExchange().toSnapshot()
                .toConversation(StringResponseHandler.instance);
        List<ToolResultBlock> results = new ArrayList<>();
        for (Message message : restored.getMessages()) {
            if (message instanceof OutgoingMessage<?> outgoing) {
                for (ContentBlock block : outgoing.getContentBlocks()) {
                    if (block instanceof ToolResultBlock tr) {
                        results.add(tr);
                    }
                }
            }
        }
        assertEquals(1, results.size(), "exactly the matched result survives");
        assertEquals("toolu_matched", results.get(0).toolUseId(),
                "the orphan was dropped: one unmatched tool_result makes the whole conversation"
                        + " unanswerable at the provider, so incomplete beats unanswerable");
    }

    @Test
    void theArtifactRegistrySurvivesTheSnapshotRoundTrip() {
        ConversationContext conversation = conversationWithToolExchange();
        ai.redouble.nucleo.harness.artifacts.WebPageArtifact artifact = new ai.redouble.nucleo.harness.artifacts.WebPageArtifact();
        artifact.setUrl("https://example.org/evidence");
        artifact.setTitle("Evidence page");
        conversation.getArtifactRegistry().register(artifact);
        String ref = artifact.getArtifactRef();
        assertNotNull(ref, "registration assigns the ref the restored registry must resolve");
        ConversationContext restored = conversation.toSnapshot().toConversation(StringResponseHandler.instance);
        assertNotNull(restored.getArtifactRegistry().get(ref),
                "the registry survives message compaction AND persistence - a restored"
                        + " conversation resolves the refs its transcript still mentions");
    }

    @Test
    void dehydratedMessageRefusesToParse_untilRehydrated() throws Exception {
        IncomingMessage<String> stored = new IncomingMessage<>(StringResponseHandler.instance);
        stored.overwriteRawContent("the answer");
        IncomingMessage<?> restored = (IncomingMessage<?>) MessageSnapshot.fromMessage(stored).toMessage(null);
        assertTrue(restored.isDehydrated(), "persistence loses the transient handler");
        assertThrows(DehydratedException.class, restored::getResponse,
                "parsing without a handler is refused, never guessed");
        @SuppressWarnings("unchecked")
        IncomingMessage<String> typed = (IncomingMessage<String>) restored;
        typed.rehydrate(StringResponseHandler.instance);
        assertEquals("the answer", typed.getResponse(), "rehydration restores parsing");
    }

    @Test
    void incomingRawContentFallsBackToItsTextBlocks() {
        IncomingMessage<String> message = new IncomingMessage<>(StringResponseHandler.instance);
        message.setContentBlocks(List.of(new TextBlock("first "), new TextBlock("second")));
        assertEquals("first second", message.getRawContent(),
                "with no raw text set, the text blocks are the content");
    }

    @Test
    void addTextSkipsNullAndBlank() {
        OutgoingMessage<String> message = new OutgoingMessage<>(StringResponseHandler.instance);
        message.addText(null);
        message.addText("   ");
        assertTrue(message.getContentBlocks().isEmpty(), "an empty block is not content");
    }
}
