/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.thinking.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins {@link ConversationService#forceRelease}'s cancellation half with a REAL dispatched
 * turn: a thinker parked mid-loop owns the conversation; forceRelease cancels that turn's
 * whole workflow and reports the cancelled job - it does NOT release the holder itself.
 * The dying turn's own finally releases it, so no second turn can acquire the context
 * while the cancelled one still mutates it; once the handle settles, the conversation
 * is free.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-18)
 */
class ConversationForceReleaseTest {
    private static final String USER = "force-release-test-user";

    @BeforeAll
    static void startDispatcher() {
        JobDispatcher.getInstance().start();
    }

    /** A turn that signals when it owns the conversation, then parks until cancelled. */
    private static final class ParkedThinker extends AbstractThinker<VoidThinkerInput, VoidThinkerOutput> {
        final CountDownLatch turnRunning = new CountDownLatch(1);

        ParkedThinker(Identifiable parent) {
            super(parent, new ThinkerDeclaration(Grade.SMALL, OutputSize.COMPACT));
        }

        @Override
        protected List<Class<? extends Tool>> declareDefaultTools() {
            return List.of();
        }

        @Override
        public Depth getDepth() {
            return Depth.STANDARD;
        }

        @Override
        protected void runThinkingLoop(ConversationContext conversation, JobContext<VoidThinkerOutput> context)
                throws LLMReadableCheckedException {
            turnRunning.countDown();
            // Cancellation is cooperative: watch the flag exactly as a real thinking
            // loop does between rounds, then surface the cancellation.
            while (!context.isCancelled()) {
                try {
                    Thread.sleep(20);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            throw new JobCancelledException(new CancellationException("turn force-released"));
        }

        @Override
        protected VoidThinkerOutput getResult() {
            return null;
        }
    }

    @Test
    void forceReleaseCancelsTheOwningTurn_whoseOwnFinallyFreesTheConversation() throws Exception {
        ParkedThinker turn = new ParkedThinker(Job.workflow(USER, "force-release-test"));
        String minted = turn.mintConversationId();
        JobHandle<VoidThinkerOutput> handle = JobDispatcher.getInstance().submit(turn);
        assertTrue(turn.turnRunning.await(15, TimeUnit.SECONDS),
                "the dispatched turn acquired the conversation and parked mid-loop");
        assertSame(turn, ConversationService.getInstance().owningThinker(minted, USER),
                "the live turn owns the conversation");

        String cancelledJob = ConversationService.getInstance().forceRelease(minted, USER);

        assertEquals(turn.getJobId(), cancelledJob, "forceRelease names the job whose workflow it cancelled");
        try {
            handle.get();
            fail("a cancelled turn must not complete normally");
        }
        catch (Exception expected) {
            // the cancellation surfacing through the handle IS the contract - the turn died
        }
        // The dying turn's own finally released the holder; poll past the settle-then-release window
        long deadline = System.currentTimeMillis() + 5_000;
        while (ConversationService.getInstance().owningThinker(minted, USER) != null
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertNull(ConversationService.getInstance().owningThinker(minted, USER),
                "ownership dies with the cancelled turn - released by ITS finally, not by forceRelease");
    }
}
