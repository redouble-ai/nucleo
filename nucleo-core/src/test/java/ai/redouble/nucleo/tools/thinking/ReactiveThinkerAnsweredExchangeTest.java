/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.tools.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An answered exchange leaves two messages in the conversation: the user's turn and the
 * model's reply as it arrived. The thinker delivers the answer to the user and adds no
 * assistant turn of its own: a second assistant message holding the answer's text would be
 * replayed after the reply on every later call, and a model that reads its earlier turns as
 * "the reply object, then the same answer as prose" writes its next reply that way.
 *
 * <p>The client is a stub behind the call's {@code client(resources)} seam; the turn itself,
 * from the inbox to the delivered answer, runs for real through the dispatcher.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-11-10)
 */
public class ReactiveThinkerAnsweredExchangeTest {

    @BeforeAll
    static void startDispatcher() {
        JobDispatcher.getInstance().start();
    }

    @Test
    void anAnsweredExchangeIsTheUsersTurnAndTheModelsReply_nothingElse() throws Exception {
        AnsweringThinker thinker = new AnsweringThinker(Job.workflow("answered-exchange-test-user", "answered-exchange-test"));
        assertTrue(thinker.addMessage("the question"), "the message is queued before the turn starts");
        JobDispatcher.getInstance().submit(thinker).get(30, TimeUnit.SECONDS);
        assertEquals("THE-ANSWER", thinker.delivered, "the user is given the answer the model stated");
        List<Message> messages = thinker.conversation.getMessages();
        assertEquals(2, messages.size(), "the user's turn and the model's reply: " + messages);
        assertEquals("user", messages.get(0).getRole());
        assertInstanceOf(IncomingMessage.class, messages.get(1),
                "the one assistant turn is the reply the model gave, never a message the thinker wrote in its name");
        assertEquals(AnsweringThinker.REPLY, messages.get(1).getRawContent(), "kept as it arrived");
    }

    private static final class AnsweringThinker extends ReactiveThinker<ChatAnswer> {
        static final String REPLY = "{\"final_answer\": true, \"answer\": {\"message_text\": \"THE-ANSWER\"}}";
        private final StandardModelSpec spec = new StandardModelSpec();
        ConversationContext conversation;
        String delivered;

        AnsweringThinker(Identifiable parent) {
            super(parent, new ThinkerDeclaration(Grade.SMALL, OutputSize.COMPACT), ChatAnswer.class);
            spec.setId("answered-exchange-fake");
            spec.setIdentity("answered-exchange-fake");
            spec.setProviderKey("openai");
            spec.setWireModelId("answered-exchange-fake");
            spec.setGrade(Grade.SMALL);
            spec.setMaxContextTokens(100_000);
            spec.setMaxOutputTokens(32_000);
            spec.setTpm(1_000_000);
            spec.setRpm(10_000);
            pinModel(spec);
        }

        @Override
        protected List<Class<? extends Tool>> declareDefaultTools() {
            return List.of();
        }

        @Override
        protected void initializeConversation(ConversationContext conversation, JobContext<VoidThinkerOutput> context) {
            this.conversation = conversation;
        }

        @Override
        protected <R> LLMCall<R> newLLMCall(Identifiable parent, ConversationContext conversation) {
            LLMCallCorrectionTest.CannedClient client = new LLMCallCorrectionTest.CannedClient(REPLY);
            client.setModel(spec);
            LLMCall<R> call = new LLMCall<>(parent, conversation) {
                @Override
                protected LLMClient client(JobResources resources) {
                    return client;
                }
            };
            call.pinModel(spec);
            return call;
        }

        @Override
        protected void streamResponse(ChatAnswer answer, JobContext<VoidThinkerOutput> context) {
            delivered = answer.getMessageText();
        }
    }
}
