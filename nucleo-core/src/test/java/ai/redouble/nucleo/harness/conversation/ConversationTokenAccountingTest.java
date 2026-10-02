/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.models.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins what {@link ConversationContext#getTotalTokens} counts: the objective's rendered
 * blocks, every message, and the artifact registry section - each contributing the moment
 * it exists - and what it deliberately does NOT count: the response instructions appended
 * at render, whose size the compaction trigger's headroom absorbs. The count is a fresh
 * estimate on every call, never accumulated state.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-17)
 */
class ConversationTokenAccountingTest {

    private static final ModelSpec MODEL = TestModels.small();

    private static OutgoingMessage<String> userText(String text) {
        OutgoingMessage<String> message = new OutgoingMessage<>(StringResponseHandler.instance);
        message.setRole("user");
        message.addText(text);
        return message;
    }

    @Test
    void objectiveMessagesAndRegistryEachCount() {
        ConversationContext conversation = new ConversationContext();
        assertEquals(0, conversation.getTotalTokens(MODEL), "an empty conversation costs nothing");
        conversation.putMainObjective("task", "extract every port label from the diagrams");
        int withObjective = conversation.getTotalTokens(MODEL);
        assertTrue(withObjective > 0, "the objective is always sent, so it always counts");
        conversation.addMessage(userText("The ringports changed to five and seven yesterday."));
        int withMessage = conversation.getTotalTokens(MODEL);
        assertTrue(withMessage > withObjective, "every message counts");
        WebPageArtifact artifact = new WebPageArtifact();
        artifact.setUrl("https://example.org/ports");
        artifact.setTitle("Port assignments");
        conversation.getArtifactRegistry().register(artifact);
        assertTrue(conversation.getTotalTokens(MODEL) > withMessage,
                "the registry section is appended at render, so it counts the moment an artifact exists");
    }

    @Test
    void responseInstructionsAreNotPartOfTheEstimate() {
        ConversationContext prose = new ConversationContext();
        prose.addMessage(userText("summarize the incident"));
        ConversationContext structured = new ConversationContext();
        OutgoingMessage<ConversationPreparationTest.ProbeAnswer> ask =
                new OutgoingMessage<>(new PojoResponseHandler<>(ConversationPreparationTest.ProbeAnswer.class));
        ask.setRole("user");
        ask.addText("summarize the incident");
        structured.addMessage(ask);
        assertEquals(prose.getTotalTokens(MODEL), structured.getTotalTokens(MODEL),
                "the handler's rendered instructions are not counted - the same text estimates"
                        + " the same whatever contract answers it, and the trigger's headroom absorbs"
                        + " the difference at the wire");
    }
}
