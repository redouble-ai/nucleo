/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import ai.redouble.nucleo.harness.models.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the enrichment-source contract: {@link ConversationContext#firstUserUtterance()}
 * is what the user first ASKED. Application-authored context riding the user channel
 * (a scoped preamble injected at initialization, {@link Message#isAppAuthored()}) wears
 * the user role for the API but is not an utterance - it must never become the stored
 * conversation's title, embedding, or objective. The flag survives the persistence
 * round-trip, so a restored conversation keeps the distinction.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-26)
 */
public class FirstUserUtteranceTest {

    @Test
    void appAuthoredPreambleIsNotTheFirstUtterance() {
        ConversationContext conversation = TestModels.conversation(TestModels.small());
        conversation.getMessages().add(preamble("PROJECT: Acme Demo (ID: 201)\nCRITICAL: All tools ..."));
        conversation.getMessages().add(userText("Are you working?"));
        assertEquals("Are you working?", conversation.firstUserUtterance(),
                "enrichment derives from what the human asked, never the injected preamble");
    }

    @Test
    void preambleAloneMeansNoUtteranceYet() {
        ConversationContext conversation = TestModels.conversation(TestModels.small());
        conversation.getMessages().add(preamble("PROJECT: Acme Demo (ID: 201)"));
        assertNull(conversation.firstUserUtterance(),
                "a conversation holding only injected context has not been spoken to yet");
    }

    @Test
    void titleDerivesFromTheHumanMessage() {
        ConversationContext conversation = TestModels.conversation(TestModels.small());
        conversation.getMessages().add(preamble("PROJECT: Acme Demo (ID: 201)\nCRITICAL: All tools ..."));
        conversation.getMessages().add(userText("Are you working?"));
        assertEquals("Are you working?", conversation.getTitle());
    }

    @Test
    void longUtteranceTitleTruncatesAtFiftyCharacters() {
        ConversationContext conversation = TestModels.conversation(TestModels.small());
        String utterance = "This question runs well past the fifty character title budget and keeps going";
        conversation.getMessages().add(userText(utterance));
        assertEquals(utterance.substring(0, 50) + "...", conversation.getTitle(),
                "a generated title is the utterance's first fifty characters, marked as cut");
    }

    @Test
    void unspokenConversationTitlesAsUntitled() {
        ConversationContext conversation = TestModels.conversation(TestModels.small());
        assertEquals("Untitled Conversation", conversation.getTitle(),
                "no utterance yet, no invented title");
    }

    @Test
    void appAuthoredFlagSurvivesThePersistenceRoundTrip() {
        Message restoredPreamble = roundTrip(preamble("injected context"));
        Message restoredUtterance = roundTrip(userText("real question"));
        assertTrue(restoredPreamble.isAppAuthored(), "the preamble stays app-authored after restore");
        assertFalse(restoredUtterance.isAppAuthored(), "the human message stays an utterance after restore");
    }

    private static Message roundTrip(Message message) {
        return MessageSnapshot.fromMessage(message).toMessage(StringResponseHandler.instance);
    }

    private static OutgoingMessage<String> preamble(String text) {
        OutgoingMessage<String> message = userText(text);
        message.setAppAuthored(true);
        return message;
    }

    private static OutgoingMessage<String> userText(String text) {
        OutgoingMessage<String> message = new OutgoingMessage<>(StringResponseHandler.instance);
        message.setRole("user");
        message.addText(text);
        return message;
    }
}
