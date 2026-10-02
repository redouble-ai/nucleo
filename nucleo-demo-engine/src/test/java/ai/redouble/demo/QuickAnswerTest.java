/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.observability.*;
import ai.redouble.nucleo.tools.builtin.*;
import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The quick question answers with what it cost and who answered: the calls the job made are
 * summed in their currency, the last call names the entry and the model that served it, and a
 * call on an entry with no price leaves the cost unstated rather than zero. The calls are the
 * ones the job's own completion event carries, and an answer whose event never arrives fails.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
class QuickAnswerTest {

    private static QuickLLMQuestionOutput output() {
        QuickLLMQuestionOutput output = new QuickLLMQuestionOutput();
        output.setAnswer("4");
        output.setReasoning("two plus two");
        return output;
    }

    @Test
    void theAnswerCarriesTheEntryTheServedModelAndTheSummedCost() {
        QuickAnswer answer = QuickAnswer.answered(output(), List.of(
                new QuickAnswer.Call("claude-haiku-4-5-bedrock", "claude-haiku-4-5", new Cost(0.0012, "USD")),
                new QuickAnswer.Call("claude-haiku-4-5-bedrock", "claude-haiku-4-5", new Cost(0.0003, "USD"))));
        assertEquals("4", answer.getAnswer());
        assertEquals("two plus two", answer.getReasoning());
        assertEquals("claude-haiku-4-5-bedrock", answer.getModel(), "the catalog entry, the name the page shows");
        assertEquals("claude-haiku-4-5", answer.getServedModel());
        assertEquals(0.0015, answer.getCost(), 1e-12, "a retried call spent its tokens too");
        assertEquals("USD", answer.getCurrency());
    }

    /** A capture over a hand-driven subscription: the test delivers the events and sees the unsubscribe. */
    private static final class Bus {
        JobObserver<JobEvent> observer;
        boolean unsubscribed;

        QuickAnswer.Capture capture(String jobId) {
            return new QuickAnswer.Capture(o -> {
                observer = o;
                return new MessageBus.Subscription() {
                    @Override
                    public void unsubscribe() {
                        unsubscribed = true;
                    }

                    @Override
                    public String getId() {
                        return "test-subscription";
                    }
                };
            }, jobId);
        }

        void deliver(JobEvent event) {
            if (observer.getPredicate().test(event)) {
                observer.observe(event);
            }
        }
    }

    private static JobSnapshot snapshot(String jobId) {
        return new JobSnapshot(jobId, null, "wf", "u", QuickLLMQuestionTool.class, JobType.TOOL, "Quick Question", null, null, null, 1,
                Instant.now(), null, null, List.of(), Map.of());
    }

    @Test
    void theCallsComeFromTheJobsOwnCompletionEvent() {
        Bus bus = new Bus();
        List<LLMResponse<?>> theirs = new ArrayList<>();
        List<LLMResponse<?>> ours = new ArrayList<>();
        try (QuickAnswer.Capture capture = bus.capture("job-ours")) {
            bus.deliver(new JobCompletedEvent<>(snapshot("job-theirs"), output(), 1, theirs, Map.of()));
            bus.deliver(new JobCompletedEvent<>(snapshot("job-ours"), output(), 1, ours, Map.of()));
            assertSame(ours, capture.awaitCalls(), "the calls this job's completion event carried, never another job's,"
                    + " and never the job's context, which the dispatcher releases once the job settles");
        }
        assertTrue(bus.unsubscribed, "closing the capture ends the subscription");
    }

    @Test
    void anAnswerWhoseCompletionEventNeverArrivesFails() {
        Bus bus = new Bus();
        try (QuickAnswer.Capture capture = bus.capture("job-ours")) {
            IllegalStateException failed = assertThrows(IllegalStateException.class, () -> capture.answer(output()));
            assertTrue(failed.getMessage().contains("without its completion event"), failed.getMessage());
        }
    }

    @Test
    void anUnpricedCallLeavesTheCostUnstated() {
        QuickAnswer answer = QuickAnswer.answered(output(), List.of(new QuickAnswer.Call("local-model", "local-model", null)));
        assertEquals("local-model", answer.getModel());
        assertNull(answer.getCost(), "no price is no cost to state, never a zero");
        assertNull(answer.getCurrency());
    }
}
