/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.tools.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the abnormal-end discipline: the conversation rolls back to the LAST user
 * utterance - the failed exchange's partial turns (possibly an unpaired tool_use,
 * which a provider rejects wholesale on the next call) must survive neither in the
 * live context nor in the store - and the exchange's outcome is recorded as a
 * marker: the LLM-readable message when the failure carries one, the generic
 * fallback otherwise, so a resumed model never sees a user message hanging
 * unanswered without explanation. The marker is a note on the user side, added to
 * the turn of the utterance that went unanswered, where the next utterance joins
 * it; the thinker never writes an assistant turn the model did not give.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-25)
 */
public class ReactiveThinkerAbnormalEndTest {

    @Test
    void rollsBackToTheUtteranceAndAddsTheMarkerToItsTurn() {
        ProbeReactive thinker = new ProbeReactive(root());
        ConversationContext conversation = TestModels.conversation(TestModels.small());
        thinker.appendUserMessage(conversation, "first question");
        conversation.getMessages().add(reply("first answer"));
        thinker.appendUserMessage(conversation, "second question");
        conversation.getMessages().add(reply("partial tool_use turn"));
        conversation.getMessages().add(debris("half-written results"));
        thinker.saveAbnormalEnd(conversation, "MARKER-TEXT");
        List<Message> messages = conversation.getMessages();
        assertEquals(3, messages.size(), "everything after the second question is gone, and no message took its place");
        OutgoingMessage<?> unanswered = (OutgoingMessage<?>) messages.get(2);
        assertEquals("user", unanswered.getRole(), "the marker is a note on the user side, never an assistant turn the model did not give");
        assertEquals(List.of(new TextBlock("second question"), new TextBlock("MARKER-TEXT")), unanswered.getContentBlocks(),
                "the marker follows the utterance it is about, in that utterance's turn");
    }

    @Test
    void theNextUtteranceJoinsTheTurnOfTheUnansweredOne() {
        ProbeReactive thinker = new ProbeReactive(root());
        ConversationContext conversation = TestModels.conversation(TestModels.small());
        thinker.appendUserMessage(conversation, "the question");
        conversation.getMessages().add(reply("partial tool_use turn"));
        thinker.saveAbnormalEnd(conversation, "MARKER-TEXT");
        thinker.appendUserMessage(conversation, "the next question");
        List<Message> messages = conversation.getMessages();
        assertEquals(1, messages.size(), "one user turn: what went unanswered, why, and what is asked now");
        OutgoingMessage<?> turn = (OutgoingMessage<?>) messages.getFirst();
        assertEquals(List.of(new TextBlock("the question"), new TextBlock("MARKER-TEXT"), new TextBlock("the next question")),
                turn.getContentBlocks());
        assertFalse(turn.isCompactable(), "a turn carrying a person's words stays verbatim through compaction");
    }

    @Test
    void injectedUtteranceAdvancesTheRollbackAnchor() {
        ProbeReactive thinker = new ProbeReactive(root());
        ConversationContext conversation = TestModels.conversation(TestModels.small());
        thinker.appendUserMessage(conversation, "trigger");
        // an injection lands at an iteration boundary, after the model's turn: it opens a user turn of its own
        conversation.getMessages().add(reply("iteration one"));
        thinker.appendUserMessage(conversation, "steering injection");
        conversation.getMessages().add(reply("iteration two"));
        thinker.saveAbnormalEnd(conversation, "MARKER-TEXT");
        List<Message> messages = conversation.getMessages();
        assertEquals(3, messages.size(), "only the debris after the injected utterance goes");
        assertEquals(List.of(new TextBlock("steering injection"), new TextBlock("MARKER-TEXT")),
                ((OutgoingMessage<?>) messages.getLast()).getContentBlocks(),
                "the injected utterance is the anchor - it survives, and the marker joins its turn");
    }

    @Test
    void markerCarriesTheLlmReadableMessageWhenThereIsOne() {
        String marker = ReactiveThinker.markerFor(new RuntimeException(new CorrectableRuntimeLLMException("the index is rebuilding")), "FALLBACK");
        assertTrue(marker.contains("the index is rebuilding"), "the LLM-readable cause reaches the conversation: " + marker);
        assertEquals("FALLBACK", ReactiveThinker.markerFor(new RuntimeException("internal detail"), "FALLBACK"),
                "a raw exception leaves only the generic marker - internals stay out of model context");
    }

    @Test
    void cancellationIsLlmReadable() {
        String marker = ReactiveThinker.markerFor(new JobContext.CancellationException("user pressed stop"), "FALLBACK");
        assertTrue(marker.contains("cancelled"), "on resume the model knows the attempt was cut short: " + marker);
        assertTrue(marker.contains("user pressed stop"));
    }

    private static Identifiable root() {
        return Job.workflow("abnormal-end-test-user", "abnormal-end-test");
    }

    /** A reply of the model, stored the way the client stores one. */
    private static IncomingMessage<String> reply(String text) {
        IncomingMessage<String> message = new IncomingMessage<>(StringResponseHandler.instance);
        message.overwriteRawContent(text);
        return message;
    }

    private static OutgoingMessage<String> debris(String text) {
        OutgoingMessage<String> message = new OutgoingMessage<>(StringResponseHandler.instance);
        message.setRole("user");
        message.addText(text);
        return message;
    }

    private static final class ProbeReactive extends ReactiveThinker {
        ProbeReactive(Identifiable parent) {
            super(parent, new ThinkerDeclaration(Grade.SMALL, OutputSize.COMPACT));
        }

        @Override
        protected List<Class<? extends Tool>> declareDefaultTools() {
            return List.of();
        }

        @Override
        protected void initializeConversation(ConversationContext conversation, JobContext<VoidThinkerOutput> context) {
            // Not used - the abnormal-end discipline is exercised directly.
        }
    }
}
