/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation.compaction;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.models.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the compaction contract of {@link LLMContextCompactor} and the
 * {@link ContextWindowManager} ladder - the "persistent agents survive long
 * conversations" promise:
 *
 * <ul>
 *   <li>Non-compactable messages (user inputs, final answers) survive every level
 *       VERBATIM - same instances, never rewritten.</li>
 *   <li>The latest outgoing message is never touched below AGGRESSIVE, and even there
 *       only when it is a compactable tool result.</li>
 *   <li>LIGHT replaces only tool results; MODERATE collapses compactable segments to
 *       summaries; AGGRESSIVE collapses everything compactable into one summary
 *       message that rides a USER turn (summaries derive from untrusted content).</li>
 *   <li>The window manager compacts nothing that is marked non-compactable, walks the
 *       whole ladder before giving up, accepts an over-optimal-but-under-model-limit
 *       result, and only then raises {@link ContextOverflowException}.</li>
 *   <li>Failure discipline follows the level: below MAXIMUM a failed summary keeps the
 *       original messages (best effort); at MAXIMUM the LLM-readable failure propagates
 *       as itself.</li>
 * </ul>
 *
 * <p>The LLM seams return canned summaries, so what is asserted is selection and
 * preservation - the compactor's actual judgment - not model output.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-31)
 */
public class CompactionContractTest {

    private static final String CANNED = "CANNED-SUMMARY";
    private static final Identifiable ROOT = Job.workflow("compaction-test", "compaction-test");

    /** Compactor whose LLM is a constant - selection logic runs for real, summaries are canned. */
    static final class CannedCompactor extends LLMContextCompactor {
        CannedCompactor() {
            super(ROOT);
        }

        @Override
        Map<Integer, String> executeParallelCompaction(List<SegmentTask> tasks, List<Message> messages) {
            Map<Integer, String> results = new HashMap<>();
            for (SegmentTask task : tasks) {
                task.segment().compactedText = CANNED;
                if (task.type() == LLMContextCompactor.CompactionType.TOOL_RESULTS_ONLY) {
                    for (int idx : task.segment().toolResultIndices) {
                        results.put(idx, CANNED);
                    }
                }
                else {
                    for (int idx : task.segment().messageIndices) {
                        results.put(idx, CANNED);
                    }
                }
            }
            return results;
        }

        @Override
        String summarize(String prompt, CompactionType type) {
            return CANNED;
        }
    }

    private static OutgoingMessage<String> user(String text, boolean compactable) {
        OutgoingMessage<String> msg = new OutgoingMessage<>(StringResponseHandler.instance);
        msg.setRole("user");
        msg.addText(text);
        msg.setCompactable(compactable);
        return msg;
    }

    private static OutgoingMessage<String> toolResult(String name, String payload) {
        OutgoingMessage<String> msg = new OutgoingMessage<>(StringResponseHandler.instance);
        msg.setRole("user");
        msg.addText("[" + name + " result]: {" + payload + "}");
        return msg;
    }

    private static IncomingMessage<String> assistant(String text, boolean compactable) {
        IncomingMessage<String> msg = new IncomingMessage<>(StringResponseHandler.instance);
        msg.overwriteRawContent(text);
        msg.setCompactable(compactable);
        return msg;
    }

    /**
     * The canonical long exchange: question, two tool rounds with answers, latest question.
     * Index:  0 user(question, PRESERVED) - 1 tool result - 2 assistant(answer, PRESERVED)
     *         3 tool result - 4 assistant (compactable) - 5 user(latest, the anchor)
     */
    private ConversationContext conversation() {
        ConversationContext context = TestModels.conversation(TestModels.small());
        // the fit check reserves the seat's declared output: a seat with a compact answer, no thinking
        context.setDepth(Depth.IMMEDIATE);
        context.setOutputDeclaration(OutputDeclaration.of(OutputSize.COMPACT));
        context.getMessages().add(user("USER-QUESTION", false));
        context.getMessages().add(toolResult("search", "\"hits\": 42"));
        context.getMessages().add(assistant("ASSISTANT-ANSWER", false));
        context.getMessages().add(toolResult("fetch", "\"body\": \"long\""));
        context.getMessages().add(assistant("intermediate reasoning", true));
        context.getMessages().add(user("LATEST-QUESTION", true));
        return context;
    }

    @Test
    void lightReplacesOnlyToolResults_everythingElseIsTheSameInstance() {
        ConversationContext context = conversation();
        List<Message> before = List.copyOf(context.getMessages());
        new CannedCompactor().compact(context, CompactionLevel.LIGHT);
        List<Message> after = context.getMessages();
        assertEquals(before.size(), after.size(), "LIGHT keeps the conversation's shape");
        assertSame(before.get(0), after.get(0), "the user's question is untouchable");
        assertTrue(after.get(1).getRawContent().contains(CANNED), "the verbose tool result shrank");
        assertSame(before.get(2), after.get(2), "the assistant's answer is untouchable");
        assertTrue(after.get(3).getRawContent().contains(CANNED));
        assertSame(before.get(5), after.get(5), "the latest interaction is never touched");
    }

    @Test
    void moderatePreservesNonCompactablesVerbatim_andSummarizesTheRest() {
        ConversationContext context = conversation();
        List<Message> before = List.copyOf(context.getMessages());
        new CannedCompactor().compact(context, CompactionLevel.MODERATE);
        List<Message> after = context.getMessages();
        assertTrue(after.contains(before.get(0)), "user question survives as the same instance");
        assertTrue(after.contains(before.get(2)), "final answer survives as the same instance");
        assertTrue(after.contains(before.get(5)), "the latest outgoing survives");
        assertTrue(after.stream().anyMatch(m -> CANNED.equals(m.getRawContent())),
                "compactable segments collapse into summary messages");
        assertFalse(after.contains(before.get(1)), "the compactable tool result is gone, represented by the summary");
    }

    @Test
    void aggressiveCollapsesToOneSummary_onAUserTurn_preservingTheProtected() {
        ConversationContext context = conversation();
        List<Message> before = List.copyOf(context.getMessages());
        new CannedCompactor().compact(context, CompactionLevel.AGGRESSIVE);
        List<Message> after = context.getMessages();
        assertEquals(4, after.size(), "non-compactables + one summary + the latest outgoing");
        assertSame(before.get(0), after.get(0));
        assertSame(before.get(2), after.get(1));
        Message summary = after.get(2);
        assertTrue(summary.getRawContent().contains("=== CONVERSATION SUMMARY ==="));
        assertTrue(summary.getRawContent().contains(CANNED));
        assertInstanceOf(OutgoingMessage.class, summary,
                "a summary derives from untrusted content and must ride a user turn, never system");
        assertEquals("user", summary.getRole(), "the summary's role IS user, not merely outgoing");
        assertSame(before.get(5), after.get(3), "the anchor outgoing message closes the transcript");
    }

    /** Compactor whose every summary call fails - the failure-discipline fixture. */
    static final class FailingCompactor extends LLMContextCompactor {
        final UncorrectableRuntimeLLMException refusal =
                new UncorrectableRuntimeLLMException("Compaction LLM returned no summary - cannot compact the context");

        FailingCompactor() {
            super(ROOT);
        }

        @Override
        Map<Integer, String> executeParallelCompaction(List<SegmentTask> tasks, List<Message> messages) {
            return Map.of();
        }

        @Override
        String summarize(String prompt, CompactionType type) {
            throw refusal;
        }
    }

    @Test
    void aggressiveFailureIsBestEffort_theOriginalsSurvive() {
        ConversationContext context = conversation();
        List<Message> before = List.copyOf(context.getMessages());
        new FailingCompactor().compact(context, CompactionLevel.AGGRESSIVE);
        assertEquals(before, context.getMessages(),
                "below MAXIMUM a failed summary degrades: the conversation is exactly as it arrived,"
                        + " never half-rewritten, and the ladder above decides what to try next");
    }

    @Test
    void maximumFailureIsNotBestEffort_theLLMReadablePropagates() {
        ConversationContext context = conversation();
        FailingCompactor compactor = new FailingCompactor();
        UncorrectableRuntimeLLMException surfaced = assertThrows(UncorrectableRuntimeLLMException.class,
                () -> compactor.compact(context, CompactionLevel.MAXIMUM),
                "MAXIMUM is the last line before the overflow refusal - its failure must be loud");
        assertSame(compactor.refusal, surfaced,
                "the LLM-readable failure travels as itself, not wrapped in a raw exception");
    }

    @Test
    void anInterruptIsNeverBestEffort_itPropagatesAndReRaisesTheFlag() {
        final class InterruptingCompactor extends LLMContextCompactor {
            InterruptingCompactor() {
                super(ROOT);
            }

            @Override
            String summarize(String prompt, CompactionType type) throws Exception {
                throw new InterruptedException("turn cancelled");
            }
        }
        ConversationContext context = conversation();
        try {
            assertThrows(UncorrectableRuntimeLLMException.class,
                    () -> new InterruptingCompactor().compact(context, CompactionLevel.AGGRESSIVE),
                    "a dying turn must stop compacting - even a best-effort level propagates the interrupt");
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag is re-raised, never eaten");
        }
        finally {
            assertTrue(Thread.interrupted(), "clear the flag so the next test starts clean");
        }
    }

    @Test
    void compactionNeverTouchesTheArtifactRegistry() {
        ConversationContext context = conversation();
        ai.redouble.nucleo.harness.artifacts.WebPageArtifact artifact = new ai.redouble.nucleo.harness.artifacts.WebPageArtifact();
        artifact.setUrl("https://example.org/evidence");
        context.getArtifactRegistry().register(artifact);
        new CannedCompactor().compact(context, CompactionLevel.AGGRESSIVE);
        assertEquals(1, context.getArtifactRegistry().getAllArtifacts().size(),
                "compaction is a message-history operation - the registry is not its business");
    }

    @Test
    void aNonCompactableConversationIsNeverCompacted() {
        ConversationContext context = conversation();
        context.setCompactable(false);
        ContextWindowManager manager = new ContextWindowManager(TestModels.small(), ROOT, null, null);
        assertFalse(manager.needsCompaction(context),
                "conversation-level opt-out wins regardless of size");
    }

    /** Window manager whose ladder runs a compactor the test controls. */
    static final class ManagedWindow extends ContextWindowManager {
        private final ContextCompactor compactor;

        ManagedWindow(ModelSpec model, ContextCompactor compactor) {
            super(model, ROOT, null, null);
            this.compactor = compactor;
        }

        @Override
        ContextCompactor newCompactor() {
            return compactor;
        }
    }

    /** A compactor that achieves nothing, for the ladder-exhaustion paths. */
    static final class FutileCompactor implements ContextCompactor {
        int invocations;

        @Override
        public ConversationContext compact(ConversationContext context, CompactionLevel level) {
            invocations++;
            return context;
        }
    }

    private ConversationContext hugeConversation(int approxTokens) {
        ConversationContext context = TestModels.conversation(TestModels.small());
        // a seat answering at the ceiling leaves the least room for input: the hard limit is
        // the model's context minus that reserve
        context.setDepth(Depth.IMMEDIATE);
        context.setOutputDeclaration(OutputDeclaration.of(OutputSize.MAX));
        // cl100k ~4 chars per token; one big user turn takes the context over any threshold
        context.getMessages().add(user("x".repeat(approxTokens * 4), true));
        context.getMessages().add(assistant("a", true));
        context.getMessages().add(user("latest", true));
        return context;
    }

    @Test
    void ladderExhaustionRaisesOverflow_afterTryingEveryLevel() {
        ModelSpec model = TestModels.small();
        int beyondEverything = model.getMaxContextTokens() + 50_000;
        FutileCompactor futile = new FutileCompactor();
        ManagedWindow manager = new ManagedWindow(model, futile);
        assertThrows(ContextOverflowException.class,
                () -> manager.ensureFits(hugeConversation(beyondEverything)),
                "when nothing can shrink and the model limit is exceeded, the overflow is loud");
        assertEquals(4, futile.invocations, "every level of the ladder was attempted first");
    }

    @Test
    void overOptimalButUnderModelLimitIsAccepted() throws Exception {
        ModelSpec model = TestModels.small();
        int optimal = Math.min(128_000, model.getMaxContextTokens());
        int overThresholdUnderLimit = (int)(optimal * 0.95);
        int absolute = model.getMaxContextTokens() - model.getMaxOutputTokens();
        // The fixture only makes sense on a model whose absolute limit leaves headroom
        // above the compaction threshold; TestModels.small() does.
        assertTrue(overThresholdUnderLimit < absolute, "fixture sanity: " + overThresholdUnderLimit + " < " + absolute);
        ManagedWindow manager = new ManagedWindow(model, new FutileCompactor());
        ConversationContext context = hugeConversation(overThresholdUnderLimit);
        assertSame(context, manager.ensureFits(context),
                "a context past the performance optimum but within the model's window is accepted, not refused");
    }
}
