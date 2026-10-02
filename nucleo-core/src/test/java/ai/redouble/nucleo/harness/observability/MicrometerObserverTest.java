/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.observability;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.llm.*;
import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.*;
import ch.qos.logback.core.read.*;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.simple.*;
import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static ai.redouble.nucleo.harness.observability.ObservabilityFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MicrometerObserver} hands every event to its {@link MeterCustomizer} and survives one
 * that throws; {@link DefaultMeterCustomizer} records the job counters and duration timer by
 * type and outcome, the LLM counters and latency timer by model, and the limiter acquire,
 * wait and reject meters, with {@code unknown} standing in for a missing tag value and a
 * timeout or cancellation counted as a failure of its own error class.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class MicrometerObserverTest {

    private static LLMResponse<String> response(String model, Integer input, Integer output, Integer cacheRead, Integer cacheWrite, long latencyMs) {
        ConversationContext conversation = new ConversationContext();
        OutgoingMessage<String> message = new OutgoingMessage<>(StringResponseHandler.instance);
        message.setRole("user");
        message.addText("ping");
        conversation.getMessages().add(message);
        LLMResponse<String> response = new LLMResponse<>(new LLMRequest<>(conversation));
        response.setModel(model);
        response.setActualInputTokens(input);
        response.setActualOutputTokens(output);
        response.setCacheReadInputTokens(cacheRead);
        response.setCacheCreationInputTokens(cacheWrite);
        response.setStartTime(Instant.now().minusMillis(latencyMs));
        response.setEndTime(Instant.now());
        return response;
    }

    private static double count(MeterRegistry registry, String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void jobEventsBecomeCountersAndADurationTimerByTypeAndOutcome() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerObserver observer = new MicrometerObserver(registry, new DefaultMeterCustomizer());
        JobSnapshot tool = snapshot("t", null, "wf", JobType.TOOL, JobState.COMPLETED, "tool");
        observer.observe(new JobStartedEvent(tool, 1));
        observer.observe(new JobCompletedEvent<>(tool, "ok", 1, List.of(), Map.of()));
        observer.observe(new JobFailedEvent(tool, new IllegalStateException("boom"), 1, List.of(), Map.of()));
        observer.observe(new JobTimedOut(tool, Duration.ofSeconds(5), 1, List.of(), Map.of()));
        observer.observe(new JobCancelled(tool, false, 1, List.of(response("m", 10, 1, null, null, 5)), Map.of()));
        assertEquals(1, count(registry, "nucleo.job.started", "job.type", "Job"), "the type tag is the job class's simple name");
        assertEquals(1, count(registry, "nucleo.job.completed", "job.type", "Job"));
        assertEquals(1, count(registry, "nucleo.job.failed", "job.type", "Job", "error.class", "IllegalStateException"));
        assertEquals(1, count(registry, "nucleo.job.failed", "job.type", "Job", "error.class", "timeout"), "a timeout is a failure of class timeout");
        assertEquals(1, count(registry, "nucleo.job.failed", "job.type", "Job", "error.class", "cancelled"), "a cancellation is a failure of class cancelled");
        for (String outcome : List.of("completed", "failed", "timeout", "cancelled")) {
            assertEquals(1, registry.find("nucleo.job.duration").tags("job.type", "Job", "outcome", outcome).timer().count(), outcome);
        }
        assertEquals(0, count(registry, "nucleo.llm.calls", "model", "m"), "a cancelled job records no LLM metrics");
    }

    @Test
    void llmResponsesOnATerminalEventBecomeCountersAndALatencyTimerByModel() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerObserver observer = new MicrometerObserver(registry, new DefaultMeterCustomizer());
        JobSnapshot tool = snapshot("t", null, "wf", JobType.TOOL, JobState.COMPLETED, "tool");
        observer.observe(new JobCompletedEvent<>(tool, "ok", 1,
                List.of(response("m", 100, 20, 30, 40, 250), response(null, null, null, null, null, 10)), Map.of()));
        assertEquals(1, count(registry, "nucleo.llm.calls", "model", "m"));
        assertEquals(100, count(registry, "nucleo.llm.input_tokens", "model", "m"));
        assertEquals(20, count(registry, "nucleo.llm.output_tokens", "model", "m"));
        assertEquals(30, count(registry, "nucleo.llm.cache_read_tokens", "model", "m"));
        assertEquals(40, count(registry, "nucleo.llm.cache_creation_tokens", "model", "m"));
        assertEquals(1, registry.find("nucleo.llm.latency").tags("model", "m").timer().count());
        assertEquals(1, count(registry, "nucleo.llm.calls", "model", "unknown"), "a response with no model is tagged unknown");
        assertNull(registry.find("nucleo.llm.input_tokens").tags("model", "unknown").counter(), "a null token count records nothing");
    }

    @Test
    void limiterTransitionsBecomeAcquireWaitAndRejectMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerObserver observer = new MicrometerObserver(registry, new DefaultMeterCustomizer());
        JobSnapshot s = snapshot("j", "wf", JobState.RUNNING);
        observer.observe(limiter(s, "pubmed", "elastic_window", 10, 1, 0, LimiterEvent.Type.GRANTED_IMMEDIATE, 0, null, null, 1));
        observer.observe(limiter(s, "pubmed", "elastic_window", 10, 2, 0, LimiterEvent.Type.GRANTED_FROM_HOLD, 3_000_000, null, null, 1));
        observer.observe(limiter(s, "pubmed", "elastic_window", 10, 2, 0, LimiterEvent.Type.REJECTED, 2_000_000, "cancelled", null, 1));
        observer.observe(limiter(s, "pubmed", "elastic_window", 10, 2, 0, LimiterEvent.Type.REJECTED, 0, null, null, 1));
        observer.observe(limiter(s, "pubmed", "elastic_window", 10, 3, 1, LimiterEvent.Type.HELD, 0, null, null, 1));
        observer.observe(limiter(s, "pubmed", "elastic_window", 10, 1, 0, LimiterEvent.Type.RELEASED, 0, null, null, 1));
        assertEquals(1, count(registry, "nucleo.limiter.acquire", "limiter", "pubmed", "category", "elastic_window", "outcome", "immediate"));
        assertEquals(1, count(registry, "nucleo.limiter.acquire", "limiter", "pubmed", "category", "elastic_window", "outcome", "held"));
        assertEquals(2, registry.find("nucleo.limiter.wait").tags("limiter", "pubmed").timer().count(),
                "the wait is recorded on a grant from hold and on a rejection after a hold, never on a rejection with no wait");
        assertEquals(1, count(registry, "nucleo.limiter.reject", "limiter", "pubmed", "category", "elastic_window", "reason", "cancelled"));
        assertEquals(1, count(registry, "nucleo.limiter.reject", "limiter", "pubmed", "category", "elastic_window", "reason", "unknown"), "no reason is tagged unknown");
        assertEquals(5, registry.getMeters().size(), "HELD and RELEASED record nothing: two acquire counters, two reject counters and one wait timer are all the meters");
        for (Meter meter : registry.getMeters()) {
            for (io.micrometer.core.instrument.Tag tag : meter.getId().getTags()) {
                assertFalse(tag.getKey().contains("job"), "no meter carries a job tag: " + meter.getId());
                assertNotEquals("j", tag.getValue(), "no meter carries the job id as a value: " + meter.getId());
            }
        }
    }

    @Test
    void aCustomizerThatThrowsIsLoggedAndTheObserverGoesOn() {
        ListAppender<ILoggingEvent> warnings = capture(MicrometerObserver.class.getName());
        try {
            List<JobEvent> seen = new ArrayList<>();
            MicrometerObserver observer = new MicrometerObserver(new SimpleMeterRegistry(), (event, registry) -> {
                seen.add(event);
                throw new IllegalStateException("registry closed");
            });
            JobStartedEvent first = new JobStartedEvent(snapshot("a", "wf", JobState.RUNNING), 1);
            JobStartedEvent second = new JobStartedEvent(snapshot("b", "wf", JobState.RUNNING), 1);
            assertDoesNotThrow(() -> observer.observe(first));
            assertDoesNotThrow(() -> observer.observe(second));
            assertEquals(List.of(first, second), seen, "every event still reaches the customizer");
            assertEquals(2, warnings.list.size());
            assertEquals(Level.WARN, warnings.list.get(0).getLevel());
            assertTrue(warnings.list.get(0).getFormattedMessage().contains("registry closed"), warnings.list.get(0).getFormattedMessage());
        }
        finally {
            release(MicrometerObserver.class.getName(), warnings);
        }
    }
}
