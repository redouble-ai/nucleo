/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The turn a conversation composes for the caller: every add ({@code addText}, {@code addPojo},
 * {@code addToolResult}, {@code addUtterance}) writes into the last message when it is an
 * outgoing user-role one and opens a new one otherwise, so a tool's results and the ask that
 * follows them are one message; the response handler and the output request set through the
 * conversation land on that same turn, the last one set winning; an utterance keeps its turn
 * verbatim through compaction; the model's reply closes the turn, so the next add opens the
 * next one; a cache policy on the conversation renders every outgoing turn with a cache
 * breakpoint; and a turn with no handler is refused at the exchange, before anything is sent.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-15)
 */
class ConversationTurnTest {

    @Test
    void addsLandInOneTurnUntilTheModelAnswers() {
        ConversationContext conversation = TestModels.conversation(TestModels.small());
        conversation.addToolResult("call-1", "{\"ok\":true}", false);
        conversation.addText("What next?");
        conversation.setResponseHandler(StringResponseHandler.instance);
        assertEquals(1, conversation.getMessages().size(), "the results and the ask are one message, the one the model answers");
        OutgoingMessage<?> turn = conversation.getLastOutgoingMessage();
        assertEquals("user", turn.getRole());
        assertInstanceOf(ToolResultBlock.class, turn.getContentBlocks().getFirst(), "a tool result stays first in the turn");
        assertSame(StringResponseHandler.instance, turn.getResponseHandler(), "the handler set through the conversation is the turn's");
        conversation.addMessage(new IncomingMessage<>(StringResponseHandler.instance));
        conversation.addText("And then?");
        assertEquals(3, conversation.getMessages().size(), "the model's reply closed the turn, so the next add opened a new one");
        assertNotSame(turn, conversation.getLastOutgoingMessage());
    }

    @Test
    void theLastHandlerSetIsTheOneThatParses_andTheOutputRequestRidesTheSameTurn() {
        ConversationContext conversation = TestModels.conversation(TestModels.small());
        conversation.addText("Answer briefly.");
        conversation.setResponseHandler(StringResponseHandler.instance);
        PojoResponseHandler<Probe> typed = new PojoResponseHandler<>(Probe.class);
        conversation.setResponseHandler(typed);
        conversation.setRequestedOutputTokens(321);
        OutgoingMessage<?> turn = conversation.getLastOutgoingMessage();
        assertSame(typed, turn.getResponseHandler(), "a second set replaces the first in place, like any setter");
        assertEquals(321, turn.getRequestedOutputTokens(), "the output request is the turn's, not a message of its own");
        assertEquals(1, conversation.getMessages().size());
    }

    @Test
    void anUtteranceKeepsItsTurnVerbatim() {
        ConversationContext conversation = TestModels.conversation(TestModels.small());
        conversation.addText("Context the application wrote.");
        conversation.addUtterance("What the person actually said.");
        OutgoingMessage<?> turn = conversation.getLastOutgoingMessage();
        assertFalse(turn.isCompactable(), "a turn carrying a person's words is never summarized away");
        assertEquals(1, conversation.getMessages().size(), "the utterance joined the turn already open");
    }

    @Test
    void theCachePolicyRendersEveryOutgoingTurnWithABreakpoint() {
        ConversationContext conversation = TestModels.conversation(TestModels.small());
        conversation.addText("first");
        conversation.setResponseHandler(StringResponseHandler.instance);
        assertFalse(conversation.render(new GenericContentFormatter(), false).turns().getFirst().cacheEnabled(),
                "without the policy a turn renders uncached unless its message says otherwise");
        conversation.setCacheTurns(true);
        assertTrue(conversation.render(new GenericContentFormatter(), false).turns().getFirst().cacheEnabled(),
                "with the policy every outgoing turn carries the breakpoint, whoever added its blocks");
        assertFalse(conversation.getLastOutgoingMessage().isEnableCache(), "the policy is the conversation's, not written onto the message");
    }

    @Test
    void aTurnWithoutAHandlerIsRefusedAtTheExchange() {
        ConversationContext conversation = TestModels.conversation(TestModels.small());
        conversation.addText("Nobody asked for an answer shape.");
        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> new ai.redouble.nucleo.tools.ResponseCorrection().exchange(null, conversation),
                "a turn that nobody asked to be parsed is refused before any call is made");
        assertTrue(refusal.getMessage().contains("setResponseHandler"), refusal.getMessage());
    }

    public static class Probe {
        private String answer;

        public String getAnswer() {
            return answer;
        }

        public void setAnswer(String answer) {
            this.answer = answer;
        }
    }
}
