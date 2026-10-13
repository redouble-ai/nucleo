/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation.compaction;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.models.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the prompt and job contract of the compaction summaries, asserted without an LLM:
 * the summary model reads a tool call with its input and a tool result with its payload,
 * never a placeholder; each prompt carries the artifact-reference preservation order (the
 * registry's «artifact:type~id» refs must stay resolvable after the prose around them is
 * compacted); an exchange prompt's and a tool result prompt's terseness instruction follows
 * the exchange's age quartile; an opening prompt shows only the messages its summary replaces;
 * the surrounding context is clipped to 300 characters a message; a summary task's prompt
 * passes through verbatim; the job is a utility job at STANDARD depth and STANDARD output
 * that builds its one conversation once and re-wires a fresh binding per attempt under its
 * 6-minute hung-call guard; and a blank summary is refused rather than inserted.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-17)
 */
class CompactionPromptContractTest {
    private static final Identifiable ROOT = Job.workflow("compaction-prompt-test", "compaction-prompt-test");
    private static final String PRESERVATION_ORDER = "Preserve any artifact references";
    private static final String PAYLOAD = "{\"ref\": \"«artifact:link:cite~a1b2c3»\", \"hits\": 3}";

    private static List<Message> toolLoop() {
        return List.of(
                CompactionContractTest.user("find it", true),
                CompactionContractTest.assistantCalling("searching", true, "A"),
                resultWith("A", PAYLOAD),
                CompactionContractTest.assistant("found", true),
                CompactionContractTest.user("thanks", true));
    }

    private static OutgoingMessage<String> resultWith(String toolUseId, String payload) {
        OutgoingMessage<String> msg = new OutgoingMessage<>(StringResponseHandler.instance);
        msg.setRole("user");
        msg.addToolResult(toolUseId, payload, false);
        return msg;
    }

    private static LLMContextCompactor.Exchange toolExchange(List<Message> messages, double ageWeight) {
        LLMContextCompactor.Exchange exchange = LLMContextCompactor.exchanges(messages).get(1);
        exchange.ageWeight = ageWeight;
        return exchange;
    }

    private static String prompt(LLMContextCompactor.CompactionTask task, List<Message> messages) {
        return new CompactionJob(ROOT, task, List.of(), messages, Grade.SMALL).buildPrompt();
    }

    @Test
    void theSummaryModelReadsTheCallAndThePayload_neverAPlaceholder() {
        List<Message> messages = toolLoop();
        LLMContextCompactor.Exchange exchange = toolExchange(messages, 0.9);
        ToolResultBlock result = (ToolResultBlock)((OutgoingMessage<?>)messages.get(2)).getContentBlocks().getFirst();
        String light = prompt(new LLMContextCompactor.ToolResultTask(exchange, (OutgoingMessage<?>)messages.get(2), result), messages);
        assertTrue(light.contains(PAYLOAD), "the LIGHT prompt shows the result's payload");
        assertTrue(light.contains("(search)"), "and names the tool from the call on the exchange");
        assertTrue(light.contains("[Tool Use search]"), "and shows the call that asked for it");
        String moderate = prompt(new LLMContextCompactor.ExchangeTask(exchange), messages);
        assertTrue(moderate.contains(PAYLOAD) && moderate.contains("[Tool Use search]"), "the MODERATE prompt shows the whole exchange as the model read it");
        assertFalse(moderate.contains("[Tool Result: A]"), "the placeholder form never reaches the summary model");
        String aggressive = new LLMContextCompactor(ROOT).buildAggressiveSummaryPrompt(messages);
        assertTrue(aggressive.contains("[Tool Use search]") && aggressive.contains(PAYLOAD));
        String recent = new LLMContextCompactor(ROOT).buildRecentExchangePrompt(exchange);
        assertTrue(recent.contains(PAYLOAD));
    }

    @Test
    void everySummaryPromptOrdersArtifactReferencesPreserved() {
        List<Message> messages = toolLoop();
        LLMContextCompactor.Exchange exchange = toolExchange(messages, 0.9);
        ToolResultBlock result = (ToolResultBlock)((OutgoingMessage<?>)messages.get(2)).getContentBlocks().getFirst();
        assertTrue(prompt(new LLMContextCompactor.ToolResultTask(exchange, (OutgoingMessage<?>)messages.get(2), result), messages).contains(PRESERVATION_ORDER),
                "the LIGHT prompt carries the preservation order");
        assertTrue(prompt(new LLMContextCompactor.ExchangeTask(exchange), messages).contains(PRESERVATION_ORDER),
                "the MODERATE prompt carries the preservation order");
        assertTrue(new LLMContextCompactor(ROOT).buildAggressiveSummaryPrompt(messages).contains(PRESERVATION_ORDER),
                "the AGGRESSIVE one-summary prompt carries the preservation order");
        assertTrue(new LLMContextCompactor(ROOT).buildRecentExchangePrompt(exchange).contains(PRESERVATION_ORDER),
                "the anchor exchange prompt carries the preservation order");
    }

    @Test
    void ageQuartileSetsTheExchangePromptsTerseness() {
        List<Message> messages = toolLoop();
        assertTrue(prompt(new LLMContextCompactor.ExchangeTask(toolExchange(messages, 0.1)), messages).contains("OLDEST 25%"),
                "the oldest quarter is asked for 1-2 lines");
        assertTrue(prompt(new LLMContextCompactor.ExchangeTask(toolExchange(messages, 0.4)), messages).contains("older half"),
                "the second quarter is asked to be concise");
        assertTrue(prompt(new LLMContextCompactor.ExchangeTask(toolExchange(messages, 0.6)), messages).contains("relatively recent"),
                "the third quarter keeps moderate detail");
        assertTrue(prompt(new LLMContextCompactor.ExchangeTask(toolExchange(messages, 0.9)), messages).contains("preserve most details"),
                "the newest quarter keeps most details");
    }

    @Test
    void theToolResultPromptCarriesTheAgeInstructionToo() {
        List<Message> messages = toolLoop();
        ToolResultBlock result = (ToolResultBlock)((OutgoingMessage<?>)messages.get(2)).getContentBlocks().getFirst();
        assertTrue(prompt(new LLMContextCompactor.ToolResultTask(toolExchange(messages, 0.1), (OutgoingMessage<?>)messages.get(2), result), messages).contains("OLDEST 25%"),
                "an old tool result is asked for 1-2 lines");
        assertTrue(prompt(new LLMContextCompactor.ToolResultTask(toolExchange(messages, 0.9), (OutgoingMessage<?>)messages.get(2), result), messages).contains("preserve most details"),
                "a recent one keeps most details");
    }

    @Test
    void theOpeningPromptShowsOnlyTheMessagesItSummarizes() {
        List<Message> messages = List.of(
                CompactionContractTest.user("COMPACTABLE-BACKGROUND", true),
                CompactionContractTest.user("PINNED-QUESTION", false),
                CompactionContractTest.assistant("found", true),
                CompactionContractTest.user("thanks", true));
        LLMContextCompactor.Exchange opening = LLMContextCompactor.exchanges(messages).getFirst();
        assertNull(opening.assistant, "the opening has no assistant turn");
        String prompt = prompt(new LLMContextCompactor.ExchangeTask(opening), List.of());
        assertTrue(prompt.contains("COMPACTABLE-BACKGROUND"), "the message the summary replaces is in the prompt");
        assertFalse(prompt.contains("PINNED-QUESTION"), "a pinned opening message stays verbatim, so the summary model never sees it as material");
    }

    @Test
    void theSurroundingContextIsClippedTo300Characters() {
        List<Message> messages = toolLoop();
        String longText = "x".repeat(350) + "TAIL-MARKER";
        List<Message> window = List.of(CompactionContractTest.user(longText, true), messages.get(1), messages.get(2));
        LLMContextCompactor.Exchange exchange = toolExchange(messages, 0.9);
        String prompt = new CompactionJob(ROOT, new LLMContextCompactor.ExchangeTask(exchange), window, messages, Grade.SMALL).buildPrompt();
        assertTrue(prompt.contains("x".repeat(300) + "..."), "a neighbouring message is clipped to 300 characters");
        assertFalse(prompt.contains("TAIL-MARKER"), "what lies past the clip is not sent");
    }

    @Test
    void theContextWindowIsFiveMessagesBeforeTheExchangeAndFourAfterItsFirst() {
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            messages.add(CompactionContractTest.user("m" + i, true));
        }
        assertEquals(messages.subList(5, 15), LLMContextCompactor.extractContextWindow(messages, 10), "five before index 10, index 10 itself and four after it");
        assertEquals(messages.subList(0, 7), LLMContextCompactor.extractContextWindow(messages, 2), "clipped at the start of the history");
        assertEquals(messages.subList(13, 20), LLMContextCompactor.extractContextWindow(messages, 18), "clipped at its end");
    }

    @Test
    void aSummaryJobIsAUtilityJobAtStandardDepthAndStandardOutput() {
        CompactionJob job = new CompactionJob(ROOT,
                new LLMContextCompactor.SummaryTask("summarize this", LLMContextCompactor.CompactionType.AGGRESSIVE_SUMMARY),
                List.of(), List.of(), Grade.SMALL);
        assertEquals(JobType.UTILITY, job.getJobType(), "a summary is the runtime's own utility work");
        job.getRequirements();
        assertEquals(OutputDeclaration.of(OutputSize.STANDARD), job.conversation.getOutputDeclaration(), "a summary declares a STANDARD answer");
    }

    @Test
    void theJobBuildsItsConversationOnce_andRewiresAFreshBindingPerAttempt() {
        CompactionJob job = new CompactionJob(ROOT,
                new LLMContextCompactor.SummaryTask("summarize this", LLMContextCompactor.CompactionType.AGGRESSIVE_SUMMARY),
                List.of(), List.of(), Grade.SMALL);
        assertEquals(java.time.Duration.ofMinutes(6), job.getTimeout(),
                "a hung-call guard for one LLM call, far past typical latency");
        job.getRequirements();
        ConversationContext built = job.conversation;
        ModelBinding first = built.getModelBinding();
        assertEquals(Grade.SMALL, first.getGrade(), "the call resolves at the compactor's grade");
        assertEquals(Depth.STANDARD, first.getDepth(), "compaction summarizes at STANDARD depth");
        job.getRequirements();
        assertSame(built, job.conversation,
                "the conversation is built once and retained - the truncation escalation on its"
                        + " sent message must reach the re-run");
        assertNotSame(first, built.getModelBinding(), "each attempt wires a FRESH binding onto it");
    }

    @Test
    void aBlankSummaryIsAFailedCompaction_neverASummary() {
        CompactionResult blank = new CompactionResult();
        blank.setSummary("   ");
        assertThrows(ai.redouble.nucleo.harness.errors.UncorrectableRuntimeLLMException.class,
                () -> LLMContextCompactor.requireSummary(blank),
                "empty output must never REPLACE content");
        CompactionResult absent = new CompactionResult();
        assertThrows(ai.redouble.nucleo.harness.errors.UncorrectableRuntimeLLMException.class,
                () -> LLMContextCompactor.requireSummary(absent));
        CompactionResult real = new CompactionResult();
        real.setSummary("a real summary");
        assertEquals("a real summary", LLMContextCompactor.requireSummary(real));
    }

    @Test
    void aSummaryTaskPromptPassesThroughVerbatim() {
        String composed = "Create a comprehensive summary. Preserve any artifact references like «artifact:type~uuid».";
        CompactionJob job = new CompactionJob(ROOT,
                new LLMContextCompactor.SummaryTask(composed, LLMContextCompactor.CompactionType.AGGRESSIVE_SUMMARY),
                List.of(), List.of(), Grade.SMALL);
        assertEquals(composed, job.buildPrompt(), "the compactor composed it; the job sends exactly that");
    }
}
