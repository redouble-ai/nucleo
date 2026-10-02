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
 * Pins the conversation identity contract: a minted id asks for a FRESH durable
 * conversation decoupled from job lineage, an adopted id is a RESUME that must find
 * the conversation, and a thinker that does neither keeps the temporary jobId-keyed
 * default. Also pins the acquisition-side invariants the turn model rests on: the
 * live context is stamped with the acquiring turn's workflowId, the owning-thinker
 * reference is user-guarded and dies with holder ownership.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-25)
 */
public class ConversationIdentityTest {
    private static final String USER = "identity-test-user";

    @Test
    void mintedIdCreatesFreshConversationDecoupledFromJobId() {
        ProbeThinker thinker = new ProbeThinker(root());
        String minted = thinker.mintConversationId();
        assertNotEquals(thinker.getJobId(), minted);
        assertEquals(minted, thinker.getConversationId());
        assertTrue(minted.startsWith("ProbeThinker-conv-"),
                "the minted id carries the readable class prefix and the conv marker: " + minted);
        ConversationContext context = obtain(minted, thinker, false);
        try {
            assertEquals(minted, context.getConversationId());
        }
        finally {
            ConversationService.getInstance().release(thinker);
        }
    }

    @Test
    void acquisitionStampsTheCurrentTurnsWorkflowId() {
        ProbeThinker thinker = new ProbeThinker(root());
        String minted = thinker.mintConversationId();
        ConversationContext context = obtain(minted, thinker, false);
        try {
            assertEquals(thinker.getWorkflowId(), context.getWorkflowId());
        }
        finally {
            ConversationService.getInstance().release(thinker);
        }
    }

    @Test
    void adoptingAMissingConversationFails() {
        ProbeThinker thinker = new ProbeThinker(root());
        thinker.setConversationId("no-such-conversation-" + UUID.randomUUID());
        CompletionException failure = assertThrows(CompletionException.class,
                () -> obtain(thinker.getConversationId(), thinker, true));
        assertInstanceOf(SystemException.class, failure.getCause());
    }

    @Test
    void defaultIdentityStaysJobKeyed() {
        ProbeThinker thinker = new ProbeThinker(root());
        assertEquals(thinker.getJobId(), thinker.getConversationId());
    }

    @Test
    void owningThinkerIsUserGuardedAndDiesWithOwnership() {
        ProbeThinker thinker = new ProbeThinker(root());
        String minted = thinker.mintConversationId();
        obtain(minted, thinker, false);
        try {
            Thinker<?, ?> owning = ConversationService.getInstance().owningThinker(minted, USER);
            assertSame(thinker, owning);
            assertNull(ConversationService.getInstance().owningThinker(minted, "somebody-else"),
                    "a foreign principal must not reach the live thinker");
        }
        finally {
            ConversationService.getInstance().release(thinker);
        }
        assertNull(ConversationService.getInstance().owningThinker(minted, USER),
                "the reference dies with holder ownership");
    }

    @Test
    void forceReleaseIsOwnerChecked() {
        ProbeThinker thinker = new ProbeThinker(root());
        String minted = thinker.mintConversationId();
        obtain(minted, thinker, false);
        try {
            assertThrows(SecurityException.class,
                    () -> ConversationService.getInstance().forceRelease(minted, "somebody-else"),
                    "a foreign principal cannot cancel another user's turn");
            assertThrows(IllegalArgumentException.class,
                    () -> ConversationService.getInstance().forceRelease("no-such-conversation", USER),
                    "an unknown conversation is refused, not silently ignored");
        }
        finally {
            ConversationService.getInstance().release(thinker);
        }
    }

    private static Identifiable root() {
        return Job.workflow(USER, "identity-test");
    }

    private static ConversationContext obtain(String conversationId, Thinker<?, ?> thinker, boolean mustExist) {
        return ConversationService.getInstance()
                                  .obtainConversation(conversationId, thinker, StringResponseHandler.instance, mustExist)
                                  .join();
    }

    private static final class ProbeThinker extends AbstractThinker<VoidThinkerInput, VoidThinkerOutput> {
        ProbeThinker(Identifiable parent) {
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
        protected void runThinkingLoop(ConversationContext conversation, JobContext<VoidThinkerOutput> context) {
            // Not used - identity semantics are exercised against ConversationService directly.
        }

        @Override
        protected VoidThinkerOutput getResult() {
            return null;
        }
    }
}
