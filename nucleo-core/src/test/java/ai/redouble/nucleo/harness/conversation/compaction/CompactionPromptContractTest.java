/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation.compaction;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.models.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the prompt and job contract of the compaction summaries, asserted without an LLM:
 * each prompt carries the artifact-reference preservation order (the registry's
 * «artifact:type~id» refs must stay resolvable after the prose around them is compacted),
 * a segment prompt's terseness instruction follows the segment's age quartile, a summary
 * task's prompt passes through verbatim, the job builds its one conversation once and
 * re-wires a fresh binding per attempt under its 6-minute hung-call guard, and a blank
 * summary is refused rather than inserted.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-17)
 */
class CompactionPromptContractTest {

    private static final Identifiable ROOT = Job.workflow("compaction-prompt-test", "compaction-prompt-test");
    private static final String PRESERVATION_ORDER = "Preserve any artifact references";

    private static OutgoingMessage<String> toolResult(String payload) {
        OutgoingMessage<String> msg = new OutgoingMessage<>(StringResponseHandler.instance);
        msg.setRole("user");
        msg.addText("[search result]: {" + payload + "}");
        return msg;
    }

    private static LLMContextCompactor.MessageSegment segmentAged(List<Message> messages, int position, int total) {
        LLMContextCompactor.MessageSegment segment = new LLMContextCompactor.MessageSegment();
        segment.startIdx = 0;
        segment.endIdx = messages.size();
        for (int i = 0; i < messages.size(); i++) {
            segment.outgoingMessages.add(messages.get(i));
            segment.messageIndices.add(i);
            segment.toolResultIndices.add(i);
        }
        segment.calculateAgeWeight(position, total);
        return segment;
    }

    private static String segmentPrompt(LLMContextCompactor.MessageSegment segment, LLMContextCompactor.CompactionType type, List<Message> messages) {
        CompactionJob job = new CompactionJob(ROOT, new LLMContextCompactor.SegmentTask(segment, type), List.of(), messages, Grade.SMALL);
        return job.buildPrompt();
    }

    @Test
    void everySummaryPromptOrdersArtifactReferencesPreserved() {
        List<Message> messages = List.of(toolResult("\"ref\": \"«artifact:link:cite~a1b2c3»\""));
        LLMContextCompactor.MessageSegment segment = segmentAged(messages, 9, 10);
        assertTrue(segmentPrompt(segment, LLMContextCompactor.CompactionType.TOOL_RESULTS_ONLY, messages).contains(PRESERVATION_ORDER),
                "the LIGHT prompt carries the preservation order");
        assertTrue(segmentPrompt(segment, LLMContextCompactor.CompactionType.FULL_SEGMENT, messages).contains(PRESERVATION_ORDER),
                "the MODERATE prompt carries the preservation order");
        assertTrue(new LLMContextCompactor(ROOT).buildAggressiveSummaryPrompt(messages).contains(PRESERVATION_ORDER),
                "the AGGRESSIVE one-summary prompt carries the preservation order");
    }

    @Test
    void ageQuartileSetsTheSegmentPromptsTerseness() {
        List<Message> messages = List.of(toolResult("\"hits\": 3"));
        assertTrue(segmentPrompt(segmentAged(messages, 1, 10), LLMContextCompactor.CompactionType.FULL_SEGMENT, messages)
                        .contains("OLDEST 25%"), "the oldest quarter is asked for 1-2 lines");
        assertTrue(segmentPrompt(segmentAged(messages, 4, 10), LLMContextCompactor.CompactionType.FULL_SEGMENT, messages)
                        .contains("older half"), "the second quarter is asked to be concise");
        assertTrue(segmentPrompt(segmentAged(messages, 6, 10), LLMContextCompactor.CompactionType.FULL_SEGMENT, messages)
                        .contains("relatively recent"), "the third quarter keeps moderate detail");
        assertTrue(segmentPrompt(segmentAged(messages, 9, 10), LLMContextCompactor.CompactionType.FULL_SEGMENT, messages)
                        .contains("preserve most details"), "the newest quarter keeps most details");
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
        CompactionJob job = new CompactionJob(ROOT,
                new LLMContextCompactor.SummaryTask("MY-COMPOSED-PROMPT", LLMContextCompactor.CompactionType.AGGRESSIVE_SUMMARY),
                List.of(), List.of(), Grade.SMALL);
        assertEquals("MY-COMPOSED-PROMPT", job.buildPrompt(),
                "the compactor composed it; the job sends exactly it");
    }
}
