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
import io.opentelemetry.api.common.*;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.*;
import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static ai.redouble.nucleo.harness.observability.ObservabilityFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link OpenTelemetryObserver}: one span per started job, named after the display name, else
 * the job type, else the id, parented on the parent job's active span; ended on the terminal
 * event with {@code ERROR} and the recorded exception for a failure and {@code OK} otherwise;
 * every other event of a job with an active span reaches the {@link SpanCustomizer}, and an
 * event without a snapshot, a job id or an active span reaches nothing; a customizer that
 * throws is logged and the observer goes on. {@link DefaultSpanCustomizer}: the identity
 * attributes on start, the attempt, duration and LLM totals on the terminal event, a span
 * event per progress message, a {@code limiter.<type>} span event per limiter transition.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class OpenTelemetryObserverTest {

    /** A span that remembers what was done to it, in place of an SDK the runtime does not carry. */
    static final class RecordingSpan implements Span {
        final String name;
        final Context parent;
        final Map<String, Object> attributes = new LinkedHashMap<>();
        final List<String> events = new ArrayList<>();
        final Map<String, Attributes> eventAttributes = new LinkedHashMap<>();
        StatusCode status;
        String statusDescription;
        Throwable recorded;
        boolean ended;

        RecordingSpan(String name, Context parent) {
            this.name = name;
            this.parent = parent;
        }

        @Override
        public <T> Span setAttribute(AttributeKey<T> key, T value) {
            attributes.put(key.getKey(), value);
            return this;
        }

        @Override
        public Span addEvent(String name, Attributes attributes) {
            events.add(name);
            eventAttributes.put(name, attributes);
            return this;
        }

        @Override
        public Span addEvent(String name, Attributes attributes, long timestamp, TimeUnit unit) {
            return addEvent(name, attributes);
        }

        @Override
        public Span setStatus(StatusCode statusCode, String description) {
            status = statusCode;
            statusDescription = description;
            return this;
        }

        @Override
        public Span recordException(Throwable exception, Attributes additionalAttributes) {
            recorded = exception;
            return this;
        }

        @Override
        public Span updateName(String name) {
            return this;
        }

        @Override
        public void end() {
            ended = true;
        }

        @Override
        public void end(long timestamp, TimeUnit unit) {
            end();
        }

        @Override
        public SpanContext getSpanContext() {
            return SpanContext.getInvalid();
        }

        @Override
        public boolean isRecording() {
            return true;
        }
    }

    /** A tracer whose builders hand out recording spans and keep every one they started. */
    static final class RecordingTracer implements Tracer {
        final List<RecordingSpan> started = new ArrayList<>();

        @Override
        public SpanBuilder spanBuilder(String spanName) {
            return new SpanBuilder() {
                Context parent;

                @Override
                public SpanBuilder setParent(Context context) {
                    parent = context;
                    return this;
                }

                @Override
                public SpanBuilder setNoParent() {return this;}

                @Override
                public SpanBuilder addLink(SpanContext spanContext) {return this;}

                @Override
                public SpanBuilder addLink(SpanContext spanContext, Attributes attributes) {return this;}

                @Override
                public SpanBuilder setAttribute(String key, String value) {return this;}

                @Override
                public SpanBuilder setAttribute(String key, long value) {return this;}

                @Override
                public SpanBuilder setAttribute(String key, double value) {return this;}

                @Override
                public SpanBuilder setAttribute(String key, boolean value) {return this;}

                @Override
                public <T> SpanBuilder setAttribute(AttributeKey<T> key, T value) {return this;}

                @Override
                public SpanBuilder setSpanKind(SpanKind spanKind) {return this;}

                @Override
                public SpanBuilder setStartTimestamp(long startTimestamp, TimeUnit unit) {return this;}

                @Override
                public Span startSpan() {
                    RecordingSpan span = new RecordingSpan(spanName, parent);
                    started.add(span);
                    return span;
                }
            };
        }
    }

    private static LLMResponse<String> response(String model, int input, int output, int cacheRead, int cacheWrite, long latencyMs) {
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

    @Test
    void aStartedJobOpensASpanNamedAndParentedFromItsSnapshotAndItsTerminalEventEndsIt() {
        RecordingTracer tracer = new RecordingTracer();
        List<JobEvent> customized = new ArrayList<>();
        OpenTelemetryObserver observer = new OpenTelemetryObserver(tracer, (event, span) -> customized.add(event));
        JobSnapshot parent = snapshot("p", null, "wf", JobType.THINKER, JobState.RUNNING, "Analyzer");
        JobSnapshot child = snapshot("c", "p", "wf", JobType.TOOL, JobState.RUNNING, null);
        JobSnapshot orphan = snapshot("o", "nobody", "wf", JobType.TOOL, JobState.RUNNING, null);
        observer.observe(new JobStartedEvent(parent, 1));
        observer.observe(new JobStartedEvent(child, 1));
        observer.observe(new JobStartedEvent(orphan, 1));
        assertEquals(3, tracer.started.size());
        RecordingSpan parentSpan = tracer.started.get(0);
        RecordingSpan childSpan = tracer.started.get(1);
        assertEquals("Analyzer", parentSpan.name, "the display name names the span");
        assertEquals("Job", childSpan.name, "without a display name, the job type does");
        assertNull(parentSpan.parent, "a top-level job has no parent span");
        assertSame(parentSpan, Span.fromContext(childSpan.parent), "a child job's span is parented on its parent job's span");
        assertNull(tracer.started.get(2).parent, "a parent id with no active span leaves the span unparented");
        assertEquals(3, customized.size(), "the customizer sees every start on its span");
        observer.observe(new JobProgressEvent<>(child, "half", 50));
        assertEquals(4, customized.size(), "and every event of a job with an active span");
        observer.observe(new JobCompletedEvent<>(child, "ok", 1, List.of(), Map.of()));
        assertTrue(childSpan.ended, "the terminal event ends the span");
        assertEquals(StatusCode.OK, childSpan.status);
        observer.observe(new JobProgressEvent<>(child, "late", 99));
        assertEquals(5, customized.size(), "an event after the span ended reaches nothing");
        observer.observe(new JobCompletedEvent<>(child, "ok", 1, List.of(), Map.of()));
        assertEquals(5, customized.size(), "a terminal event with no active span reaches nothing");
    }

    @Test
    void aRetriedStartKeepsTheJobsOneSpan() {
        RecordingTracer tracer = new RecordingTracer();
        List<JobEvent> customized = new ArrayList<>();
        OpenTelemetryObserver observer = new OpenTelemetryObserver(tracer, (event, span) -> customized.add(event));
        JobSnapshot job = snapshot("r", "wf", JobState.RUNNING);
        observer.observe(new JobStartedEvent(job, 1));
        observer.observe(new JobStartedEvent(job, 2));
        observer.observe(new JobStartedEvent(job, 3));
        assertEquals(1, tracer.started.size(), "three attempts of one job are one span, as they are one running job");
        assertEquals(3, customized.size(), "each retry's start reaches the customizer on the existing span");
        assertEquals(3, ((JobStartedEvent) customized.get(2)).getAttempt(), "with its attempt number");
        observer.observe(new JobCompletedEvent<>(job, "ok", 3, List.of(), Map.of()));
        assertTrue(tracer.started.get(0).ended, "and that span ends on the job's terminal event");
    }

    @Test
    void aFailureEndsTheSpanInErrorWithTheExceptionRecorded() {
        RecordingTracer tracer = new RecordingTracer();
        OpenTelemetryObserver observer = new OpenTelemetryObserver(tracer, (event, span) -> {});
        JobSnapshot job = snapshot("f", "wf", JobState.RUNNING);
        observer.observe(new JobStartedEvent(job, 1));
        IllegalStateException error = new IllegalStateException("disk gone");
        observer.observe(new JobFailedEvent(job, error, 1, List.of(), Map.of()));
        RecordingSpan span = tracer.started.get(0);
        assertTrue(span.ended);
        assertEquals(StatusCode.ERROR, span.status);
        assertEquals("disk gone", span.statusDescription, "the error's message is the status description");
        assertSame(error, span.recorded, "the exception is recorded on the span");
        observer.observe(new JobStartedEvent(snapshot("t", "wf", JobState.RUNNING), 1));
        observer.observe(new JobTimedOut(snapshot("t", "wf", JobState.TIMED_OUT), Duration.ofSeconds(1), 1, List.of(), Map.of()));
        assertEquals(StatusCode.ERROR, tracer.started.get(1).status, "a timeout is a failure");
        observer.observe(new JobStartedEvent(snapshot("k", "wf", JobState.RUNNING), 1));
        observer.observe(new JobCancelled(snapshot("k", "wf", JobState.CANCELLED), false, 1, List.of(), Map.of()));
        assertEquals(StatusCode.OK, tracer.started.get(2).status, "a cancellation is not a failure");
    }

    @Test
    void eventsWithoutASnapshotOrAJobIdReachNothingAndAThrowingCustomizerIsLogged() {
        ListAppender<ILoggingEvent> warnings = capture(OpenTelemetryObserver.class.getName());
        try {
            RecordingTracer tracer = new RecordingTracer();
            OpenTelemetryObserver observer = new OpenTelemetryObserver(tracer, (event, span) -> {
                throw new IllegalStateException("exporter down");
            });
            assertDoesNotThrow(() -> observer.observe(new SchedulerEvent(SchedulerEvent.Type.STARTED, "up")));
            assertTrue(tracer.started.isEmpty(), "no snapshot, no span");
            JobSnapshot job = snapshot("j", "wf", JobState.RUNNING);
            assertDoesNotThrow(() -> observer.observe(new JobStartedEvent(job, 1)), "the customizer's failure stays inside the observer");
            assertEquals(1, tracer.started.size());
            assertEquals(Level.WARN, warnings.list.get(0).getLevel());
            assertTrue(warnings.list.get(0).getFormattedMessage().contains("exporter down"), warnings.list.get(0).getFormattedMessage());
            assertDoesNotThrow(() -> observer.observe(new JobCompletedEvent<>(job, "ok", 1, List.of(), Map.of())));
            assertTrue(tracer.started.get(0).ended, "the span still ends when the customizer throws");
        }
        finally {
            release(OpenTelemetryObserver.class.getName(), warnings);
        }
    }

    @Test
    void theDefaultCustomizerSetsIdentityOnStartTotalsOnTheEndAndEventsForProgressAndLimiters() {
        RecordingTracer tracer = new RecordingTracer();
        OpenTelemetryObserver observer = new OpenTelemetryObserver(tracer, new DefaultSpanCustomizer());
        JobSnapshot job = snapshot("j-1", "p-1", "wf-1", JobType.TOOL, JobState.RUNNING, "Fetcher");
        observer.observe(new JobStartedEvent(job, 2));
        RecordingSpan span = tracer.started.get(0);
        assertEquals("j-1", span.attributes.get("job.id"));
        assertEquals("p-1", span.attributes.get("job.parent_id"));
        assertEquals("wf-1", span.attributes.get("workflow.id"));
        assertEquals("tester", span.attributes.get("user.id"));
        assertEquals("Job", span.attributes.get("job.type"));
        assertEquals("Fetcher", span.attributes.get("job.display_name"));
        assertFalse(span.attributes.containsKey("job.action"), "a null field sets no attribute");
        assertEquals(2L, span.attributes.get("job.attempt"));
        observer.observe(new JobProgressEvent<>(job, "parsing", 30));
        assertEquals(List.of("parsing"), span.events, "a progress message is a span event");
        observer.observe(limiter(job, "pubmed", "elastic_window", 10, 3, 2, LimiterEvent.Type.GRANTED_FROM_HOLD, 4_000_000, null, null, 1));
        observer.observe(limiter(job, "pubmed", "elastic_window", 10, 3, 2, LimiterEvent.Type.REJECTED, 0, "circuit_blocked", "blocked", 1));
        assertEquals(List.of("parsing", "limiter.granted_from_hold", "limiter.rejected"), span.events);
        Map<AttributeKey<?>, Object> granted = span.eventAttributes.get("limiter.granted_from_hold").asMap();
        assertEquals("pubmed", granted.get(AttributeKey.stringKey("limiter.name")));
        assertEquals("elastic_window", granted.get(AttributeKey.stringKey("limiter.category")));
        assertEquals("GRANTED_FROM_HOLD", granted.get(AttributeKey.stringKey("limiter.type")));
        assertEquals(3L, granted.get(AttributeKey.longKey("limiter.in_use")));
        assertEquals(10L, granted.get(AttributeKey.longKey("limiter.capacity")));
        assertEquals(2L, granted.get(AttributeKey.longKey("limiter.waiters")));
        assertEquals(4L, granted.get(AttributeKey.longKey("limiter.wait_ms")), "the wait in milliseconds when there was one");
        assertFalse(granted.containsKey(AttributeKey.stringKey("limiter.reject_reason")));
        Map<AttributeKey<?>, Object> rejected = span.eventAttributes.get("limiter.rejected").asMap();
        assertEquals("circuit_blocked", rejected.get(AttributeKey.stringKey("limiter.reject_reason")));
        assertFalse(rejected.containsKey(AttributeKey.longKey("limiter.wait_ms")), "no wait, no attribute");
        observer.observe(new JobCompletedEvent<>(job, "ok", 2,
                List.of(response("m1", 100, 20, 30, 40, 250), response("m2", 1, 1, 0, 0, 50)), Map.of()));
        assertEquals(2L, span.attributes.get("job.attempts"));
        assertTrue((Long) span.attributes.get("job.duration_ms") >= 0);
        assertEquals(2L, span.attributes.get("llm.calls"));
        assertEquals(101L, span.attributes.get("llm.input_tokens"));
        assertEquals(21L, span.attributes.get("llm.output_tokens"));
        assertEquals(30L, span.attributes.get("llm.cache_read_tokens"));
        assertEquals(40L, span.attributes.get("llm.cache_creation_tokens"));
        assertTrue((Long) span.attributes.get("llm.latency_ms") >= 300, "latencies sum");
        assertEquals("m1,m2", span.attributes.get("llm.models"), "the models, comma-separated");
    }
}
