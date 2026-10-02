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
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An exchange that fails FAILS the turn: after the conversation's rollback-and-marker save
 * and the failure event, the failure propagates and the thinker job ends FAILED carrying
 * the cause - never a completed thinker over an exchange that produced nothing, which
 * would leave the user watching a silent chat while the run record reports success.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-20)
 */
public class ReactiveThinkerFailedExchangeTest {

    @BeforeAll
    static void startDispatcher() {
        JobDispatcher.getInstance().start();
    }

    @Test
    void aFailedExchangeFailsTheTurnCarryingTheCause() throws Exception {
        Identifiable root = Job.workflow("failed-exchange-test-user", "failed-exchange-test");
        FailingExchangeThinker thinker = new FailingExchangeThinker(root);
        assertTrue(thinker.addMessage("hello"), "the message is queued before the turn starts");
        JobHandle<VoidThinkerOutput> handle = JobDispatcher.getInstance().submit(thinker);
        ExecutionException failure = assertThrows(ExecutionException.class, () -> handle.get(30, TimeUnit.SECONDS),
                "the turn ends FAILED, never completed over a dead exchange");
        Throwable cause = failure.getCause();
        assertNotNull(cause);
        assertTrue(String.valueOf(cause.getMessage()).contains("the exchange is refused for this test"),
                "the turn's failure carries the exchange's own cause: " + cause);
    }

    private static final class FailingExchangeThinker extends ReactiveThinker {
        FailingExchangeThinker(Identifiable parent) {
            super(parent, new ThinkerDeclaration(Grade.SMALL, OutputSize.COMPACT));
        }

        @Override
        protected List<Class<? extends Tool>> declareDefaultTools() {
            return List.of();
        }

        @Override
        protected void initializeConversation(ConversationContext conversation, JobContext<VoidThinkerOutput> context) {
        }

        @Override
        protected void processMessage(String message, ConversationContext conversation, JobContext<VoidThinkerOutput> context) {
            throw new UncorrectableRuntimeLLMException("the exchange is refused for this test");
        }
    }
}
