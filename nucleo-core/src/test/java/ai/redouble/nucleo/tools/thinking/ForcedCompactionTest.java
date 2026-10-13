/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.retry.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.tools.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The compaction a pre-flight overflow forces respects the conversation's own flag: a
 * conversation marked non-compactable is never summarized and fails with
 * {@link NonCompactableContextException}, naming the estimate and the window, before any
 * summary call is made; a conversation that never resolved a model has nothing to account
 * under and is refused as a programming error. The retry around it compacts once and calls
 * again, turns a second overflow into {@link ContextOverflowException} with the refusal as its
 * cause, and lets any other failure through as it came. The per-turn check leaves a
 * conversation under the trigger, or one with no model to account under, as it is.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-12)
 */
class ForcedCompactionTest {

    private static class ProbeThinker extends AbstractThinker<VoidThinkerInput, VoidThinkerOutput> {
        ProbeThinker() {
            super(Job.workflow("forced-compaction-test", "forced-compaction-test"), new ThinkerDeclaration(Grade.SMALL, OutputSize.COMPACT));
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
        }

        @Override
        protected VoidThinkerOutput getResult() {
            return null;
        }
    }

    @Test
    void aNonCompactableConversationIsRefusedBeforeAnythingIsSummarized() {
        ModelSpec model = TestModels.small();
        ConversationContext conversation = TestModels.conversation(model);
        OutgoingMessage<String> message = new OutgoingMessage<>(StringResponseHandler.instance);
        message.setRole("user");
        message.addText("the whole case file ".repeat(200));
        conversation.getMessages().add(message);
        conversation.setCompactable(false);
        NonCompactableContextException refusal = assertThrows(NonCompactableContextException.class,
                () -> new ProbeThinker().forceCompaction(conversation, null),
                "the owner said no compaction; the runtime does not override that");
        assertEquals(model.estimate(conversation).inputTokens(), refusal.getEstimatedTokens(), "the refusal carries the estimate");
        assertEquals(model.getMaxContextTokens(), refusal.getMaxContextTokens(), "and the window");
        assertTrue(refusal.getMessage().contains(model.getId()), "and names the model: " + refusal.getMessage());
        assertTrue(refusal.getLLMMessage().contains("may not be compacted"), "the model is told why nothing shrank: " + refusal.getLLMMessage());
        assertFalse(refusal.isCorrectable(), "no retry with other parameters helps");
        assertEquals(1, conversation.getMessages().size(), "nothing was summarized");
    }

    /** A thinker whose forced compaction is a stand-in: it hands back the conversation it was given, shrunk by one message. */
    private static final class CompactingProbe extends ProbeThinker {
        int forced;

        @Override
        protected ConversationContext forceCompaction(ConversationContext conversation, JobContext<?> context) {
            forced++;
            conversation.getMessages().removeFirst();
            return conversation;
        }
    }

    private static ConversationContext twoTurns(ModelSpec model) {
        ConversationContext conversation = TestModels.conversation(model);
        for (String text : List.of("first", "second")) {
            OutgoingMessage<String> message = new OutgoingMessage<>(StringResponseHandler.instance);
            message.setRole("user");
            message.addText(text);
            conversation.getMessages().add(message);
        }
        return conversation;
    }

    @Test
    void aPreFlightOverflowIsCompactedOnceAndRetried_aSecondOverflowIsTheOverflowRefusal() throws Exception {
        CompactingProbe thinker = new CompactingProbe();
        JobContext<VoidThinkerOutput> context = new JobContext<>(thinker, "tester", java.time.Duration.ofMinutes(1), new JobRequirements());
        TokenEstimateExceedsLimitException first = new TokenEstimateExceedsLimitException(10_000, 8_000, "small-model");
        ConversationContext conversation = twoTurns(TestModels.small());
        AbstractThinker.CompactedReply<String> reply = thinker.compactAndRetry(first, conversation, context, compacted -> "answered");
        assertEquals(1, thinker.forced, "compacted once");
        assertSame(conversation, reply.conversation(), "the loop continues on the compacted conversation");
        assertEquals(1, reply.conversation().getMessages().size());
        assertEquals("answered", reply.response(), "and the retried call's answer is the reply");
        TokenEstimateExceedsLimitException again = new TokenEstimateExceedsLimitException(9_000, 8_000, "small-model");
        ContextOverflowException overflow = assertThrows(ContextOverflowException.class,
                () -> thinker.compactAndRetry(first, twoTurns(TestModels.small()), context, compacted -> {
                    throw new java.util.concurrent.ExecutionException(again);
                }), "a second overflow ends the run: the conversation cannot fit");
        assertSame(again, overflow.getCause(), "with the pre-flight refusal as its cause");
        IllegalStateException other = new IllegalStateException("provider down");
        java.util.concurrent.ExecutionException propagated = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> thinker.compactAndRetry(first, twoTurns(TestModels.small()), context, compacted -> {
                    throw new java.util.concurrent.ExecutionException(other);
                }), "any other failure of the retry propagates as it came");
        assertSame(other, propagated.getCause());
    }

    @Test
    void thePerTurnCheckLeavesASmallConversationAlone_andOneWithNoModelToAccountUnder() throws Exception {
        ProbeThinker thinker = new ProbeThinker();
        ConversationContext small = twoTurns(TestModels.small());
        assertSame(small, thinker.performCompactionLoop(small, null, 1), "under the trigger nothing happens, and the context is not even consulted");
        assertEquals(2, small.getMessages().size());
        ConversationContext unresolved = new ConversationContext();
        assertSame(unresolved, thinker.performCompactionLoop(unresolved, null, 1), "no model resolved and no prior: nothing to account under, returned as it is");
    }

    @Test
    void aConversationThatNeverResolvedAModelCannotBeForciblyCompacted() {
        ConversationContext conversation = new ConversationContext();
        assertThrows(IllegalStateException.class, () -> new ProbeThinker().forceCompaction(conversation, null),
                "there is no spec to account tokens under");
    }
}
