/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.observability;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.harness.*;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.*;
import org.slf4j.*;

import java.util.concurrent.*;

/**
 * Global observer that translates job lifecycle events into OpenTelemetry spans.
 *
 * <p>Maps the Nucleo job hierarchy onto OTel's trace/span model:
 * <ul>
 *   <li>One span per job: the first {@link JobStartedEvent} that carries a job id creates it,
 *       named after the snapshot's display name, else its job type, else the job id, and
 *       parented on the parent job's span when that span is active. A retry republishes the
 *       start event, which reaches the {@link SpanCustomizer} on the existing span with its
 *       attempt number and opens nothing</li>
 *   <li>Each {@link TerminalEvent} ends the corresponding span: status {@code ERROR} with the
 *       error's message and the exception recorded for a {@link FailureEvent}, {@code OK}
 *       otherwise (a cancellation is not a failure)</li>
 *   <li>All other events of a job with an active span are forwarded to the {@link SpanCustomizer}
 *       on that span; an event with no snapshot, no job id or no active span goes nowhere</li>
 * </ul>
 *
 * <p>Registered once, observes all workflows. Span attributes are NOT predefined -
 * callers provide a {@link SpanCustomizer} to decorate spans however they want. A customizer
 * that throws is logged at WARN and the observer goes on; the span still ends.
 *
 * <p>Usage:
 * <pre>{@code
 * Tracer tracer = openTelemetry.getTracer("my-app");
 * SpanCustomizer customizer = (event, span) -> {
 *     switch (event) {
 *         case JobStartedEvent e -> span.setAttribute("user.id", e.snapshot().getUserId());
 *         case JobCompletedEvent<?> e -> span.setAttribute("result.type", e.result().getClass().getName());
 *         default -> {}
 *     }
 * };
 * OpenTelemetryObserver observer = new OpenTelemetryObserver(tracer, customizer);
 * messageBus.subscribe(Job.class, JobEvent.class, observer);
 * }</pre>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-03-29)
 */
public class OpenTelemetryObserver implements JobObserver<JobEvent> {
    private static final Logger log = LoggerFactory.getLogger(OpenTelemetryObserver.class);
    private final Tracer tracer;
    private final SpanCustomizer customizer;
    private final ConcurrentHashMap<String, Span> activeSpans = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Context> spanContexts = new ConcurrentHashMap<>();

    /**
     * Creates a global observer that translates job events into OTel spans.
     *
     * @param tracer the OTel tracer to create spans with
     * @param customizer callback for decorating spans with attributes
     */
    public OpenTelemetryObserver(Tracer tracer, SpanCustomizer customizer) {
        this.tracer = tracer;
        this.customizer = customizer;
    }

    @Override
    public void observe(JobEvent event) {
        if (event instanceof JobStartedEvent started) {
            handleJobStarted(started);
        } else if (event instanceof TerminalEvent terminal) {
            handleTerminal(terminal);
        } else {
            forwardToCustomizer(event);
        }
    }

    private void handleJobStarted(JobStartedEvent event) {
        JobSnapshot snapshot = event.snapshot();
        if (snapshot == null) return;
        String jobId = snapshot.getJobId();
        if (jobId == null) return;
        // One span per job: a retry republishes the start; the customizer sees it on the
        // existing span (the attempt rides the event), and no second span opens.
        Span active = activeSpans.get(jobId);
        if (active != null) {
            invokeCustomizer(event, active, jobId);
            return;
        }
        String spanName = snapshot.getDisplayName() != null ? snapshot.getDisplayName() : snapshot.jobType();
        if (spanName == null) {
            spanName = jobId;
        }
        SpanBuilder spanBuilder = tracer.spanBuilder(spanName);

        // Wire parent-child relationship
        String parentJobId = snapshot.getParentJobId();
        if (parentJobId != null) {
            Context parentContext = spanContexts.get(parentJobId);
            if (parentContext != null) {
                spanBuilder.setParent(parentContext);
            }
        }
        Span span = spanBuilder.startSpan();
        activeSpans.put(jobId, span);
        spanContexts.put(jobId, Context.current().with(span));
        invokeCustomizer(event, span, jobId);
    }

    private void handleTerminal(TerminalEvent terminal) {
        JobSnapshot snapshot = terminal.snapshot();
        if (snapshot == null) return;
        String jobId = snapshot.getJobId();
        if (jobId == null) return;
        Span span = activeSpans.remove(jobId);
        if (span == null) return;
        spanContexts.remove(jobId);
        invokeCustomizer(terminal, span, jobId);
        if (terminal instanceof FailureEvent failure) {
            span.setStatus(StatusCode.ERROR, failure.getError() != null ? failure.getError().getMessage() : "failed");
            if (failure.getError() != null) {
                span.recordException(failure.getError());
            }
        } else {
            span.setStatus(StatusCode.OK);
        }
        span.end();
    }

    private void forwardToCustomizer(JobEvent event) {
        JobSnapshot snapshot = event.snapshot();
        if (snapshot == null) return;
        String jobId = snapshot.getJobId();
        if (jobId == null) return;
        Span span = activeSpans.get(jobId);
        if (span == null) return;
        invokeCustomizer(event, span, jobId);
    }

    private void invokeCustomizer(JobEvent event, Span span, String jobId) {
        try {
            customizer.customize(event, span);
        } catch (Exception e) {
            log.warn("SpanCustomizer threw for job {}: {}", jobId, e.getMessage());
        }
    }
}
