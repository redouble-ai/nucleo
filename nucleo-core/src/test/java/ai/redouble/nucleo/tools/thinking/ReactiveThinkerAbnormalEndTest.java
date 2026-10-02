/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
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
 * unanswered without explanation.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-25)
 */
public class ReactiveThinkerAbnormalEndTest {

    @Test
    void rollsBackToTheTriggerAndRecordsTheMarker() {
        ProbeReactive thinker = new ProbeReactive(root());
        ConversationContext conversation = TestModels.conversation(TestModels.small());
        thinker.appendUserMessage(conversation, "first question");
        conversation.getMessages().add(assistantText("first answer"));
        thinker.appendUserMessage(conversation, "second question");
        conversation.getMessages().add(debris("partial tool_use turn"));
        conversation.getMessages().add(debris("half-written results"));
        thinker.saveAbnormalEnd(conversation, "MARKER-TEXT");
        List<Message> messages = conversation.getMessages();
        assertEquals(4, messages.size(), "everything after the second question is gone, the marker took its place");
        assertEquals("second question", messages.get(2).getRawContent());
        assertEquals("assistant", messages.get(3).getRole());
        assertEquals("MARKER-TEXT", messages.get(3).getRawContent());
    }

    @Test
    void injectedUtteranceAdvancesTheRollbackAnchor() {
        ProbeReactive thinker = new ProbeReactive(root());
        ConversationContext conversation = TestModels.conversation(TestModels.small());
        thinker.appendUserMessage(conversation, "trigger");
        conversation.getMessages().add(debris("iteration one"));
        thinker.appendUserMessage(conversation, "steering injection");
        conversation.getMessages().add(debris("iteration two"));
        thinker.saveAbnormalEnd(conversation, "MARKER-TEXT");
        List<Message> messages = conversation.getMessages();
        assertEquals("steering injection", messages.get(messages.size() - 2).getRawContent(),
                "the injected utterance is the anchor - it survives, only the debris after it goes");
        assertEquals("MARKER-TEXT", messages.get(messages.size() - 1).getRawContent());
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

    private static OutgoingMessage<String> assistantText(String text) {
        OutgoingMessage<String> message = new OutgoingMessage<>(StringResponseHandler.instance);
        message.setRole("assistant");
        message.addText(text);
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
