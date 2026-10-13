/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation.compaction;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.tokens.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The compaction contract, on a conversation shaped the way the thinker builds one: native
 * tool calls on the assistant turns and native tool results on the turns that answer them.
 * <ul>
 *   <li>The unit is the exchange, an assistant turn and the turns that answer it; a tool call
 *       and its result leave together or stay together at every level, and either side marked
 *       non-compactable keeps both.</li>
 *   <li>LIGHT shrinks a tool result's payload inside its block, so ids and pairings stay; MODERATE
 *       collapses an exchange into one summary; AGGRESSIVE collapses everything compactable before
 *       the anchor into one summary and summarizes an anchor that carries tool results on its own.</li>
 *   <li>Non-compactable messages survive every level verbatim, as the same instances.</li>
 *   <li>Every level leaves no tool call without its result and no result without its call, and an
 *       orphan on either side is refused by its id.</li>
 *   <li>Failure discipline follows the level: below MAXIMUM a failed summary keeps the original
 *       messages, at MAXIMUM the LLM-readable failure propagates (a checked cause re-wrapped in its
 *       runtime sibling, a raw cause as uncorrectable), and an interrupt always propagates.</li>
 *   <li>The ladder climbs LIGHT, MODERATE, AGGRESSIVE, MAXIMUM in order, stops at the first level
 *       that brings the input under the trigger, estimates once per rung, accepts a conversation
 *       past the comfort window that still fits the model, and refuses one that does not, naming
 *       the parts and telling the model what to do.</li>
 *   <li>The opening before the first assistant turn is compacted message by message; an anchor
 *       pinned by a non-compactable message stays verbatim at every level; a shrunken message
 *       carries everything but its blocks; too little to compact is returned as it is; the
 *       conversation object, its objective and its tools are untouched; exchanges age as their
 *       index over the count; MAXIMUM runs the AGGRESSIVE collapse until gh-1 says more.</li>
 * </ul>
 * The LLM seams return canned summaries, so what is asserted is selection and preservation,
 * the compactor's own judgment, not model output.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-31)
 */
public class CompactionContractTest {

    private static final String CANNED = "CANNED-SUMMARY";
    private static final Identifiable ROOT = Job.workflow("compaction-test", "compaction-test");

    /** Compactor whose LLM is a constant - selection logic runs for real, summaries are canned. */
    static class CannedCompactor extends LLMContextCompactor {
        CannedCompactor() {
            super(ROOT);
        }

        @Override
        Map<CompactionTask, String> runSummaries(List<CompactionTask> tasks, List<Message> messages) {
            Map<CompactionTask, String> results = new LinkedHashMap<>();
            for (CompactionTask task : tasks) {
                results.put(task, CANNED);
            }
            return results;
        }

        @Override
        String summarize(String prompt, CompactionType type) {
            return CANNED;
        }
    }

    static OutgoingMessage<String> user(String text, boolean compactable) {
        OutgoingMessage<String> msg = new OutgoingMessage<>(StringResponseHandler.instance);
        msg.setRole("user");
        msg.addText(text);
        msg.setCompactable(compactable);
        return msg;
    }

    static IncomingMessage<String> assistantCalling(String text, boolean compactable, String... toolUseIds) {
        IncomingMessage<String> msg = new IncomingMessage<>(StringResponseHandler.instance);
        List<ContentBlock> blocks = new ArrayList<>();
        blocks.add(new TextBlock(text));
        for (String id : toolUseIds) {
            blocks.add(new ToolUseBlock(id, "search", "{\"q\":\"" + id + "\"}"));
        }
        msg.setContentBlocks(blocks);
        msg.overwriteRawContent(text);
        msg.setCompactable(compactable);
        return msg;
    }

    static OutgoingMessage<String> results(boolean compactable, String... toolUseIds) {
        OutgoingMessage<String> msg = new OutgoingMessage<>(StringResponseHandler.instance);
        msg.setRole("user");
        for (String id : toolUseIds) {
            msg.addToolResult(id, "{\"hits\": \"" + "verbose payload ".repeat(40) + "\", \"for\": \"" + id + "\"}", false);
        }
        msg.setCompactable(compactable);
        return msg;
    }

    static IncomingMessage<String> assistant(String text, boolean compactable) {
        return assistantCalling(text, compactable);
    }

    /**
     * The canonical tool loop of a chat. Index:
     * 0 user(question, PRESERVED) - 1 assistant calls A - 2 results A - 3 assistant calls B - 4 results B
     * 5 assistant(answer, PRESERVED) - 6 user(follow-up, PRESERVED) - 7 assistant calls C - 8 results C (the anchor)
     */
    private ConversationContext conversation() {
        // no depth and no answer size declared: compaction is about the input and asks for neither
        ConversationContext context = TestModels.conversation(TestModels.small());
        context.getMessages().add(user("USER-QUESTION", false));
        context.getMessages().add(assistantCalling("looking", true, "A"));
        context.getMessages().add(results(true, "A"));
        context.getMessages().add(assistantCalling("looking further", true, "B"));
        context.getMessages().add(results(true, "B"));
        context.getMessages().add(assistant("ASSISTANT-ANSWER", false));
        context.getMessages().add(user("FOLLOW-UP", false));
        context.getMessages().add(assistantCalling("checking", true, "C"));
        context.getMessages().add(results(true, "C"));
        return context;
    }

    private static String payloadOf(Message message, String toolUseId) {
        for (ContentBlock block : ((OutgoingMessage<?>)message).getContentBlocks()) {
            if (block instanceof ToolResultBlock tr && tr.toolUseId().equals(toolUseId)) {
                return tr.resultJson();
            }
        }
        return null;
    }

    @Test
    void theHistoryCutsIntoExchanges_oneAssistantTurnAndItsAnswers() {
        List<LLMContextCompactor.Exchange> exchanges = LLMContextCompactor.exchanges(conversation().getMessages());
        assertEquals(5, exchanges.size(), "the opening, three tool exchanges, the answer with its follow-up, the anchor");
        assertNull(exchanges.get(0).assistant, "the opening has no assistant turn");
        assertEquals(1, exchanges.get(0).followers.size());
        assertEquals("search", exchanges.get(1).toolName("A"), "a result's tool name comes from the call on its exchange");
        assertTrue(exchanges.get(1).compactable());
        assertFalse(exchanges.get(3).compactable(), "the answer and the follow-up are both preserved");
        assertTrue(exchanges.getLast().carriesToolResults(), "the anchor exchange ends in tool results");
    }

    @Test
    void lightShrinksToolResultPayloadsInsideTheirBlocks_everythingElseIsTheSameInstance() {
        ConversationContext context = conversation();
        List<Message> before = List.copyOf(context.getMessages());
        new CannedCompactor().compact(context, CompactionLevel.LIGHT);
        List<Message> after = context.getMessages();
        assertEquals(before.size(), after.size(), "LIGHT keeps the conversation's shape");
        assertSame(before.get(0), after.get(0), "the user's question is untouchable");
        assertSame(before.get(1), after.get(1), "the assistant's tool call stays as it was");
        assertEquals(CANNED, payloadOf(after.get(2), "A"), "the verbose result shrank to its summary, inside the block that keeps its id");
        assertEquals(CANNED, payloadOf(after.get(4), "B"));
        assertSame(before.get(5), after.get(5), "the assistant's answer is untouchable");
        assertSame(before.get(7), after.get(7));
        assertSame(before.get(8), after.get(8), "the anchor is never touched below AGGRESSIVE");
        assertNotEquals(CANNED, payloadOf(after.get(8), "C"));
        LLMContextCompactor.requirePaired(after);
    }

    @Test
    void moderateCollapsesEachCompactableExchange_callAndResultsTogether() {
        ConversationContext context = conversation();
        List<Message> before = List.copyOf(context.getMessages());
        new CannedCompactor().compact(context, CompactionLevel.MODERATE);
        List<Message> after = context.getMessages();
        assertSame(before.get(0), after.get(0), "the user question survives as the same instance");
        assertEquals(CANNED, after.get(1).getRawContent(), "exchange A is one summary");
        assertEquals(CANNED, after.get(2).getRawContent(), "exchange B is one summary");
        assertSame(before.get(5), after.get(3), "the final answer survives as the same instance");
        assertSame(before.get(6), after.get(4), "and so does the follow-up it pins");
        assertSame(before.get(7), after.get(5), "the anchor exchange is untouched");
        assertSame(before.get(8), after.get(6));
        assertEquals(7, after.size());
        LLMContextCompactor.requirePaired(after);
    }

    @Test
    void aggressiveCollapsesToOneSummary_andSummarizesTheToolAnchorOnItsOwn() {
        ConversationContext context = conversation();
        List<Message> before = List.copyOf(context.getMessages());
        new CannedCompactor().compact(context, CompactionLevel.AGGRESSIVE);
        List<Message> after = context.getMessages();
        assertEquals(5, after.size(), "the three preserved messages, one summary, the recent exchange");
        assertSame(before.get(0), after.get(0));
        assertSame(before.get(5), after.get(1));
        assertSame(before.get(6), after.get(2));
        Message summary = after.get(3);
        assertTrue(summary.getRawContent().contains("=== CONVERSATION SUMMARY ==="));
        assertTrue(summary.getRawContent().contains(CANNED));
        assertInstanceOf(OutgoingMessage.class, summary, "a summary derives from untrusted content and must ride a user turn, never system");
        assertEquals("user", summary.getRole(), "the summary's role IS user, not merely outgoing");
        assertTrue(after.get(4).getRawContent().startsWith("[Recent exchange]"),
                "an anchor that carries tool results is summarized together with the call that asked for them");
        LLMContextCompactor.requirePaired(after);
    }

    @Test
    void anAnchorPinnedByANonCompactableMessageStaysVerbatimAtAggressive_toolResultsOrNot() {
        ConversationContext context = conversation();
        context.getMessages().get(8).setCompactable(false);
        List<Message> before = List.copyOf(context.getMessages());
        new CannedCompactor().compact(context, CompactionLevel.AGGRESSIVE);
        List<Message> after = context.getMessages();
        assertSame(before.get(7), after.get(after.size() - 2), "the call whose results are preserved stays with them");
        assertSame(before.get(8), after.getLast(), "preserved results close the transcript as they are, never as a [Recent exchange] summary");
        LLMContextCompactor.requirePaired(after);
    }

    @Test
    void theOpeningIsCompactedMessageByMessage_itsPinnedMessagesKeepTheirPlace() {
        ConversationContext context = TestModels.conversation(TestModels.small());
        context.getMessages().add(user("BACKGROUND", true));
        context.getMessages().add(user("USER-QUESTION", false));
        context.getMessages().add(assistantCalling("looking", true, "A"));
        context.getMessages().add(results(true, "A"));
        context.getMessages().add(assistant("ASSISTANT-ANSWER", true));
        context.getMessages().add(user("LATEST", true));
        List<Message> before = List.copyOf(context.getMessages());
        new CannedCompactor().compact(context, CompactionLevel.MODERATE);
        List<Message> moderate = context.getMessages();
        assertEquals(CANNED, moderate.get(0).getRawContent(), "the opening's compactable message collapses into one summary where it stood");
        assertSame(before.get(1), moderate.get(1), "the pinned opening message keeps its place");
        assertEquals(CANNED, moderate.get(2).getRawContent(), "exchange A is one summary");
        assertSame(before.get(4), moderate.get(3), "the anchor is untouched");
        assertSame(before.get(5), moderate.get(4));
        ConversationContext again = TestModels.conversation(TestModels.small());
        again.getMessages().addAll(before);
        new CannedCompactor().compact(again, CompactionLevel.AGGRESSIVE);
        List<Message> aggressive = again.getMessages();
        assertSame(before.get(1), aggressive.get(0), "the pinned opening message leads, verbatim");
        assertTrue(aggressive.get(1).getRawContent().contains("=== CONVERSATION SUMMARY ==="), "the compactable opening message joined the one summary");
        assertFalse(aggressive.contains(before.get(0)));
        assertSame(before.get(4), aggressive.get(2), "a plain-text anchor stays");
        assertSame(before.get(5), aggressive.get(3));
    }

    @Test
    void aShrunkenMessageCarriesEverythingButItsBlocks() {
        OutgoingMessage<String> original = new OutgoingMessage<>(StringResponseHandler.instance);
        original.setRole("user");
        original.setMessageId("msg-7");
        original.setTimestamp(java.time.Instant.parse("2026-10-10T10:00:00Z"));
        original.setCompactable(false);
        original.setAppAuthored(true);
        original.setCache(true);
        original.setRequestedOutputTokens(1234);
        original.addToolResult("A", "payload", true);
        List<ContentBlock> shrunk = List.of(new ToolResultBlock("A", "summary", true));
        OutgoingMessage<String> copy = OutgoingMessage.copyWithBlocks(original, shrunk);
        assertEquals(shrunk, copy.getContentBlocks(), "the given blocks");
        assertEquals("msg-7", copy.getMessageId(), "the same identity");
        assertEquals(original.getTimestamp(), copy.getTimestamp());
        assertEquals("user", copy.getRole());
        assertTrue(copy.isEnableCache(), "the cache flag travels");
        assertFalse(copy.isCompactable(), "compactability travels");
        assertTrue(copy.isAppAuthored(), "authorship travels");
        assertEquals(1234, copy.getRequestedOutputTokens(), "the per-call output override travels");
        assertSame(original.getResponseHandler(), copy.getResponseHandler(), "and the handler");
    }

    @Test
    void tooLittleToCompact_isReturnedAsItIs() {
        ConversationContext two = TestModels.conversation(TestModels.small());
        two.getMessages().add(user("q", true));
        two.getMessages().add(assistant("a", true));
        List<Message> before = List.copyOf(two.getMessages());
        assertSame(two, new CannedCompactor().compact(two, CompactionLevel.AGGRESSIVE));
        assertEquals(before, two.getMessages(), "fewer than three messages: nothing to compact");
        ConversationContext noAnchor = TestModels.conversation(TestModels.small());
        noAnchor.getMessages().add(user("q", true));
        noAnchor.getMessages().add(assistantCalling("looking", true, "A"));
        noAnchor.getMessages().add(results(true, "A"));
        noAnchor.getMessages().add(assistant("answer", true));
        List<Message> original = List.copyOf(noAnchor.getMessages());
        new CannedCompactor().compact(noAnchor, CompactionLevel.AGGRESSIVE);
        assertEquals(original, noAnchor.getMessages(), "a transcript that ends on the assistant has no outgoing turn to anchor on: untouched");
    }

    @Test
    void compactionReturnsTheSameConversation_withItsObjectiveAndToolsAsTheyWere() {
        ConversationContext context = conversation();
        context.putMainObjective("task", "OBJECTIVE-MARKER");
        context.addTool(new ToolDefinitionBlock("search", "finds things", "{\"type\":\"object\"}"));
        GenericContentFormatter formatter = new GenericContentFormatter();
        PreparedConversation before = context.render(formatter, false);
        assertSame(context, new CannedCompactor().compact(context, CompactionLevel.AGGRESSIVE), "the list is rewritten in place; the conversation is the same object");
        PreparedConversation after = context.render(formatter, false);
        assertEquals(before.systemText(), after.systemText(), "the objective is not compaction's business");
        assertEquals(before.toolDefinitions(), after.toolDefinitions(), "nor are the tools");
    }

    @Test
    void exchangesAgeFromOldestToNewest_asTheirIndexOverTheCount() {
        List<LLMContextCompactor.Exchange> exchanges = LLMContextCompactor.exchanges(conversation().getMessages());
        for (int i = 0; i < exchanges.size(); i++) {
            assertEquals((double)i / exchanges.size(), exchanges.get(i).ageWeight, 1e-12, "exchange " + i);
        }
    }

    @Test
    void maximumRunsTheAggressiveCollapse_untilGh1DefinesWhatMoreItMayTouch() {
        ConversationContext aggressive = conversation();
        ConversationContext maximum = conversation();
        new CannedCompactor().compact(aggressive, CompactionLevel.AGGRESSIVE);
        new CannedCompactor().compact(maximum, CompactionLevel.MAXIMUM);
        assertEquals(aggressive.getMessages().stream().map(Message::getRawContent).toList(),
                maximum.getMessages().stream().map(Message::getRawContent).toList(),
                "MAXIMUM produces the AGGRESSIVE shape today; what it may touch beyond that is open on gh-1");
    }

    @Test
    void aggressiveKeepsAPlainTextAnchorAsItIs() {
        ConversationContext context = conversation();
        context.getMessages().add(assistant("intermediate", true));
        context.getMessages().add(user("LATEST", true));
        List<Message> before = List.copyOf(context.getMessages());
        new CannedCompactor().compact(context, CompactionLevel.AGGRESSIVE);
        List<Message> after = context.getMessages();
        assertSame(before.getLast(), after.getLast(), "a text anchor closes the transcript untouched");
        assertSame(before.get(before.size() - 2), after.get(after.size() - 2), "with the assistant turn it answers");
    }

    @Test
    void aNonCompactableResultsTurnPinsItsCall_andANonCompactableCallPinsItsResults() {
        ConversationContext pinnedByResults = conversation();
        pinnedByResults.getMessages().get(2).setCompactable(false);
        List<Message> before = List.copyOf(pinnedByResults.getMessages());
        new CannedCompactor().compact(pinnedByResults, CompactionLevel.MODERATE);
        assertTrue(pinnedByResults.getMessages().contains(before.get(1)), "the call whose results are preserved is preserved with them");
        assertTrue(pinnedByResults.getMessages().contains(before.get(2)));
        new CannedCompactor().compact(pinnedByResults, CompactionLevel.AGGRESSIVE);
        assertTrue(pinnedByResults.getMessages().contains(before.get(1)), "at AGGRESSIVE too");
        assertTrue(pinnedByResults.getMessages().contains(before.get(2)));
        LLMContextCompactor.requirePaired(pinnedByResults.getMessages());

        ConversationContext pinnedByCall = conversation();
        pinnedByCall.getMessages().get(3).setCompactable(false);
        List<Message> original = List.copyOf(pinnedByCall.getMessages());
        new CannedCompactor().compact(pinnedByCall, CompactionLevel.LIGHT);
        assertNotEquals(CANNED, payloadOf(pinnedByCall.getMessages().get(4), "B"), "results answering a preserved call are not shrunk");
        new CannedCompactor().compact(pinnedByCall, CompactionLevel.AGGRESSIVE);
        assertTrue(pinnedByCall.getMessages().contains(original.get(3)));
        assertTrue(pinnedByCall.getMessages().contains(original.get(4)), "the results a preserved call asked for are preserved with it");
        LLMContextCompactor.requirePaired(pinnedByCall.getMessages());
    }

    @Test
    void thePairingCheckRefusesAnOrphanOnEitherSide() {
        List<Message> orphanCall = List.of(user("q", true), assistantCalling("x", true, "A"), user("not a result", true));
        IllegalStateException call = assertThrows(IllegalStateException.class, () -> LLMContextCompactor.requirePaired(orphanCall));
        assertTrue(call.getMessage().contains("tool_use A"), call.getMessage());
        List<Message> orphanResult = List.of(user("q", true), assistant("plain", true), results(true, "B"));
        IllegalStateException result = assertThrows(IllegalStateException.class, () -> LLMContextCompactor.requirePaired(orphanResult));
        assertTrue(result.getMessage().contains("tool_result B"), result.getMessage());
        LLMContextCompactor.requirePaired(conversation().getMessages());
    }

    /** Compactor whose every summary call fails - the failure-discipline fixture. */
    static final class FailingCompactor extends LLMContextCompactor {
        final UncorrectableRuntimeLLMException refusal =
                new UncorrectableRuntimeLLMException("Compaction LLM returned no summary - cannot compact the context");

        FailingCompactor() {
            super(ROOT);
        }

        @Override
        Map<CompactionTask, String> runSummaries(List<CompactionTask> tasks, List<Message> messages) {
            return Map.of();
        }

        @Override
        String summarize(String prompt, CompactionType type) {
            throw refusal;
        }
    }

    @Test
    void aFailedSummaryBelowMaximumIsBestEffort_theOriginalsSurvive() {
        ConversationContext light = conversation();
        List<Message> before = List.copyOf(light.getMessages());
        new FailingCompactor().compact(light, CompactionLevel.LIGHT);
        assertEquals(before, light.getMessages(), "LIGHT with no summaries changes nothing");
        ConversationContext aggressive = conversation();
        new FailingCompactor().compact(aggressive, CompactionLevel.AGGRESSIVE);
        assertEquals(before.size(), aggressive.getMessages().size(),
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
        assertSame(compactor.refusal, surfaced, "the LLM-readable failure travels as itself, not wrapped in a raw exception");
    }

    @Test
    void maximumWrapsACheckedCauseInItsRuntimeSibling_andARawCauseAsUncorrectable() {
        final class CheckedFailingCompactor extends LLMContextCompactor {
            final ExternalServiceException cause = new ExternalServiceException("summary model", "unreachable");

            CheckedFailingCompactor() {
                super(ROOT);
            }

            @Override
            String summarize(String prompt, CompactionType type) throws Exception {
                throw cause;
            }
        }
        CheckedFailingCompactor checked = new CheckedFailingCompactor();
        UncorrectableRuntimeLLMException rewrapped = assertThrows(UncorrectableRuntimeLLMException.class,
                () -> checked.compact(conversation(), CompactionLevel.MAXIMUM));
        assertSame(checked.cause, rewrapped.getCause(), "a checked LLM-readable cause rides its runtime sibling, cause intact");
        assertEquals(checked.cause.getLLMMessage(), rewrapped.getLLMMessage(), "with the same message for the model");
        final class RawFailingCompactor extends LLMContextCompactor {
            final RuntimeException cause = new IllegalStateException("disk gone");

            RawFailingCompactor() {
                super(ROOT);
            }

            @Override
            String summarize(String prompt, CompactionType type) {
                throw cause;
            }
        }
        RawFailingCompactor raw = new RawFailingCompactor();
        UncorrectableRuntimeLLMException wrapped = assertThrows(UncorrectableRuntimeLLMException.class,
                () -> raw.compact(conversation(), CompactionLevel.MAXIMUM));
        assertSame(raw.cause, wrapped.getCause(), "anything else becomes uncorrectable with its cause");
        assertTrue(wrapped.getMessage().contains("MAXIMUM compaction failed"), wrapped.getMessage());
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
        assertFalse(manager.needsCompaction(context, manager.estimate(context)),
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
        // one big user turn, sized by the model's own counter, takes the context over any threshold
        String unit = "The quick brown fox jumps over the lazy dog. ";
        int unitTokens = TestModels.small().tokenCounter().countTokens(unit);
        context.getMessages().add(user(unit.repeat(approxTokens / unitTokens + 1), true));
        context.getMessages().add(assistant("a", true));
        context.getMessages().add(user("latest", true));
        return context;
    }

    @Test
    void ladderExhaustionRaisesOverflow_afterTryingEveryLevel_namingTheParts() {
        ModelSpec model = TestModels.small();
        int beyondEverything = model.getMaxContextTokens() + 50_000;
        FutileCompactor futile = new FutileCompactor();
        ManagedWindow manager = new ManagedWindow(model, futile);
        ConversationContext huge = hugeConversation(beyondEverything);
        ContextOverflowException overflow = assertThrows(ContextOverflowException.class,
                () -> manager.ensureFits(huge, manager.estimate(huge)),
                "when nothing can shrink and the model limit is exceeded, the overflow is loud");
        assertEquals(4, futile.invocations, "every level of the ladder was attempted first");
        assertTrue(overflow.getMessage().contains(ContextPart.MESSAGES.name()), "the refusal says which part filled the window: " + overflow.getMessage());
        assertTrue(overflow.getLLMMessage().contains("Start a new conversation"), "the model is told what it can do about it: " + overflow.getLLMMessage());
        assertFalse(overflow.isCorrectable(), "no parameter change fixes a conversation that cannot fit");
    }

    /** A conversation past the manager's trigger yet inside its effective limit, so the ladder runs and the result is accepted. */
    private ConversationContext conversationPastTheTrigger(ContextWindowManager manager) {
        int target = (int)(manager.effectiveLimit() * 0.97);
        int measured = manager.estimate(hugeConversation(target)).inputTokens();
        // the repeated unit tokenizes a little tighter than one copy of it, so size by the measured ratio
        ConversationContext context = hugeConversation((int)((long)target * target / measured));
        TokenLedger estimate = manager.estimate(context);
        assertTrue(manager.needsCompaction(context, estimate), "fixture: past the trigger at " + estimate.inputTokens());
        assertTrue(estimate.inputTokens() <= manager.effectiveLimit(), "fixture: inside the effective limit at " + estimate.inputTokens());
        return context;
    }

    /** A compactor that records the levels it was asked for and shrinks the conversation at one of them. */
    static final class RecordingCompactor implements ContextCompactor {
        final List<CompactionLevel> levels = new ArrayList<>();
        private final CompactionLevel shrinksAt;

        RecordingCompactor(CompactionLevel shrinksAt) {
            this.shrinksAt = shrinksAt;
        }

        @Override
        public ConversationContext compact(ConversationContext context, CompactionLevel level) {
            levels.add(level);
            if (level == shrinksAt) {
                context.getMessages().set(0, user("small", true));
            }
            return context;
        }
    }

    @Test
    void theLadderClimbsInOrder_andStopsAtTheFirstLevelThatBringsTheInputUnderTheTrigger() throws Exception {
        ModelSpec model = TestModels.small();
        RecordingCompactor compactor = new RecordingCompactor(CompactionLevel.MODERATE);
        ManagedWindow manager = new ManagedWindow(model, compactor);
        ConversationContext context = conversationPastTheTrigger(manager);
        manager.ensureFits(context, manager.estimate(context));
        assertEquals(List.of(CompactionLevel.LIGHT, CompactionLevel.MODERATE), compactor.levels,
                "LIGHT first, then MODERATE, and nothing after the level that worked");
        assertFalse(manager.needsCompaction(context, manager.estimate(context)));
    }

    /** A window manager that counts the estimates its ladder asks for. */
    static final class CountingWindow extends ContextWindowManager {
        int estimates;

        CountingWindow(ModelSpec model) {
            super(model, ROOT, null, null);
        }

        @Override
        public TokenLedger estimate(ConversationContext context) {
            estimates++;
            return super.estimate(context);
        }

        @Override
        ContextCompactor newCompactor() {
            return new FutileCompactor();
        }
    }

    @Test
    void theLadderEstimatesOncePerRung_andReadsTheCallersEstimateForItsFirst() throws Exception {
        ModelSpec model = TestModels.small();
        CountingWindow manager = new CountingWindow(model);
        ConversationContext context = conversationPastTheTrigger(manager);
        manager.estimates = 0;
        TokenLedger estimate = manager.estimate(context);
        assertEquals(1, manager.estimates);
        manager.ensureFits(context, estimate);
        assertEquals(1 + 4, manager.estimates, "the caller's estimate serves the first rung; each of the four rungs re-estimates once after compacting");
    }

    @Test
    void overOptimalButUnderModelLimitIsAccepted_onTheInputAlone() throws Exception {
        ModelSpec model = TestModels.small();
        int optimal = Math.min(128_000, model.getMaxContextTokens());
        int overThresholdUnderLimit = (int)(optimal * 0.95);
        // The fixture only makes sense on a model whose window leaves headroom above the
        // compaction threshold; TestModels.small() does.
        assertTrue(overThresholdUnderLimit < model.getMaxContextTokens(), "fixture sanity: " + overThresholdUnderLimit + " < " + model.getMaxContextTokens());
        ManagedWindow manager = new ManagedWindow(model, new FutileCompactor());
        ConversationContext context = hugeConversation(overThresholdUnderLimit);
        assertSame(context, manager.ensureFits(context, manager.estimate(context)),
                "a context past the performance optimum but within the model's window is accepted, not refused;"
                        + " the room the answer needs is the pre-flight's question, not compaction's");
    }
}
