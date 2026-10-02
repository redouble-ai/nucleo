/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.observability;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.llm.*;
import io.opentelemetry.api.common.*;
import io.opentelemetry.api.trace.*;

import java.util.*;

/**
 * Default {@link SpanCustomizer} that maps all available event fields to span attributes.
 *
 * <p>On {@link JobStartedEvent}:
 * <ul>
 *   <li>{@code job.id}, {@code job.parent_id}, {@code workflow.id}, {@code user.id}</li>
 *   <li>{@code job.type}, {@code job.display_name}, {@code job.action}</li>
 *   <li>{@code job.attempt}</li>
 * </ul>
 *
 * <p>On {@link JobProgressEvent}:
 * <ul>
 *   <li>Adds a span event with the progress message</li>
 * </ul>
 *
 * <p>On {@link AbstractTerminalEvent}:
 * <ul>
 *   <li>{@code job.attempts}, {@code job.duration_ms}</li>
 *   <li>{@code llm.calls}, {@code llm.input_tokens}, {@code llm.output_tokens}</li>
 *   <li>{@code llm.cache_read_tokens}, {@code llm.cache_creation_tokens}</li>
 *   <li>{@code llm.latency_ms}, {@code llm.models} (comma-separated)</li>
 * </ul>
 *
 * <p>On {@link LimiterEvent}:
 * <ul>
 *   <li>Adds a span event named {@code limiter.<type>} (lower case) with attributes
 *       {@code limiter.name}, {@code limiter.category}, {@code limiter.type},
 *       {@code limiter.in_use}, {@code limiter.capacity}, {@code limiter.waiters},
 *       plus {@code limiter.wait_ms} when the wait is positive and
 *       {@code limiter.reject_reason} when the event carries one</li>
 * </ul>
 *
 * <p>A null attribute value is left off the span; an event with no snapshot sets nothing.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-03-29)
 */
public class DefaultSpanCustomizer implements SpanCustomizer {

    @Override
    public void customize(JobEvent event, Span span) {
        switch (event) {
            case JobStartedEvent e -> onStarted(e, span);
            case AbstractTerminalEvent<?> e -> onTerminal(e, span);
            case JobProgressEvent<?> e -> onProgress(e, span);
            case LimiterEvent e -> onLimiter(e, span);
            default -> {}
        }
    }

    private void onStarted(JobStartedEvent event, Span span) {
        JobSnapshot s = event.snapshot();
        if (s == null) return;
        setIfNotNull(span, "job.id", s.getJobId());
        setIfNotNull(span, "job.parent_id", s.getParentJobId());
        setIfNotNull(span, "workflow.id", s.getWorkflowId());
        setIfNotNull(span, "user.id", s.getUserId());
        setIfNotNull(span, "job.type", s.jobType());
        setIfNotNull(span, "job.display_name", s.getDisplayName());
        setIfNotNull(span, "job.action", s.getAction());
        span.setAttribute("job.attempt", event.getAttempt());
    }

    private void onTerminal(AbstractTerminalEvent<?> event, Span span) {
        span.setAttribute("job.attempts", event.getAttempts());
        span.setAttribute("job.duration_ms", event.getDuration().toMillis());
        List<LLMResponse<?>> responses = event.getLlmResponses();
        if (responses == null || responses.isEmpty()) return;
        long inputTokens = 0;
        long outputTokens = 0;
        long cacheReadTokens = 0;
        long cacheCreationTokens = 0;
        long latencyMs = 0;
        StringBuilder models = new StringBuilder();
        for (LLMResponse<?> r : responses) {
            if (r.getActualInputTokens() != null) inputTokens += r.getActualInputTokens();
            if (r.getActualOutputTokens() != null) outputTokens += r.getActualOutputTokens();
            if (r.getCacheReadInputTokens() != null) cacheReadTokens += r.getCacheReadInputTokens();
            if (r.getCacheCreationInputTokens() != null) cacheCreationTokens += r.getCacheCreationInputTokens();
            latencyMs += r.getLatencyMs();
            if (r.getModel() != null) {
                if (!models.isEmpty()) models.append(",");
                models.append(r.getModel());
            }
        }
        span.setAttribute("llm.calls", (long) responses.size());
        span.setAttribute("llm.input_tokens", inputTokens);
        span.setAttribute("llm.output_tokens", outputTokens);
        span.setAttribute("llm.cache_read_tokens", cacheReadTokens);
        span.setAttribute("llm.cache_creation_tokens", cacheCreationTokens);
        span.setAttribute("llm.latency_ms", latencyMs);
        if (!models.isEmpty()) {
            span.setAttribute("llm.models", models.toString());
        }
    }

    private void onProgress(JobProgressEvent<?> event, Span span) {
        String message = event.message();
        if (message != null) {
            span.addEvent(message);
        }
    }

    private void onLimiter(LimiterEvent event, Span span) {
        AttributesBuilder attrs = Attributes.builder()
                .put("limiter.name", event.limiterName())
                .put("limiter.category", event.limiterCategory())
                .put("limiter.type", event.type().name())
                .put("limiter.in_use", event.inUse())
                .put("limiter.capacity", event.capacity())
                .put("limiter.waiters", (long) event.waiters());
        if (event.waitNanos() > 0) {
            attrs.put("limiter.wait_ms", event.waitNanos() / 1_000_000);
        }
        if (event.rejectReason() != null) {
            attrs.put("limiter.reject_reason", event.rejectReason());
        }
        span.addEvent("limiter." + event.type().name().toLowerCase(), attrs.build());
    }

    private void setIfNotNull(Span span, String key, String value) {
        if (value != null) {
            span.setAttribute(key, value);
        }
    }
}
