/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.retry.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.tools.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

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

    /**
     * The exchange's model call is a child job. When that child spends its upstream retries, the
     * turn has already told its user that it failed, so it must end there: the thinker carries a
     * retry budget of its own, and a turn run again for the child's spent signal would poll an
     * inbox the failed message has left and complete over nothing.
     */
    @Test
    void anExchangeWhoseModelCallSpentItsRetries_failsTheTurnOnceAndIsNotRunAgain() throws Exception {
        Identifiable root = Job.workflow("failed-exchange-test-user", "spent-retries-exchange-test");
        List<MessageCompleteEvent> completions = new CopyOnWriteArrayList<>();
        MessageBus.Subscription subscription = JobDispatcher.getInstance().observeWorkflow(root.getWorkflowId(), event -> {
            if (event instanceof MessageCompleteEvent completion) {
                completions.add(completion);
            }
        });
        try {
            SpentRetriesExchangeThinker thinker = new SpentRetriesExchangeThinker(root);
            thinker.setUpstreamRetries(1);
            assertTrue(thinker.addMessage("hello"), "the message is queued before the turn starts");
            ExecutionException failure = assertThrows(ExecutionException.class, () -> JobDispatcher.getInstance().submit(thinker).get(60, TimeUnit.SECONDS),
                    "the turn ends FAILED on its child's exhausted retries");
            assertInstanceOf(UncorrectableRuntimeLLMException.class, failure.getCause(), "with the child's own failure: " + failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("transparent retry budget (0)"), failure.getCause().getMessage());
            assertEquals(1, thinker.exchanges.get(), "the exchange ran once");
            assertEquals(1, thinker.turns.get(), "and the turn was not started again for the signal under the child's failure");
            assertEquals(1, completions.size(), "the user was told once: " + completions);
            assertFalse(completions.getFirst().isSuccessful(), "that the message failed");
        }
        finally {
            subscription.unsubscribe();
        }
    }

    /** A model call whose provider faults on its only attempt: upstream budget 0. */
    private static final class FaultingCall extends AbstractJob<String> {
        FaultingCall(Identifiable parent) {
            super(parent, "faulting-call-fixture");
            setUpstreamRetries(0);
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = new JobRequirements();
            req.setRequiresTransaction(false);
            return req;
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            throw new TransientErrorRetryException("fixture upstream fault", "fixture", "boom", 503, 0, null);
        }
    }

    private static final class SpentRetriesExchangeThinker extends ReactiveThinker<ChatAnswer> {
        final AtomicInteger turns = new AtomicInteger();
        final AtomicInteger exchanges = new AtomicInteger();

        SpentRetriesExchangeThinker(Identifiable parent) {
            super(parent, new ThinkerDeclaration(Grade.SMALL, OutputSize.COMPACT), ChatAnswer.class);
        }

        @Override
        protected List<Class<? extends Tool>> declareDefaultTools() {
            return List.of();
        }

        @Override
        protected void initializeConversation(ConversationContext conversation, JobContext<VoidThinkerOutput> context) {
            turns.incrementAndGet();
        }

        @Override
        protected void processMessage(String message, ConversationContext conversation, JobContext<VoidThinkerOutput> context) throws Exception {
            exchanges.incrementAndGet();
            submitInIteration(1, new FaultingCall(this)).get();
        }
    }

    private static final class FailingExchangeThinker extends ReactiveThinker<ChatAnswer> {
        FailingExchangeThinker(Identifiable parent) {
            super(parent, new ThinkerDeclaration(Grade.SMALL, OutputSize.COMPACT), ChatAnswer.class);
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
