/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.observability;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.llm.*;
import io.micrometer.core.instrument.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Default {@link MeterCustomizer} that records standard job and LLM metrics.
 *
 * <p>Metrics recorded:
 * <ul>
 *   <li>{@code nucleo.job.started} - counter, tagged by job type</li>
 *   <li>{@code nucleo.job.completed} - counter, tagged by job type</li>
 *   <li>{@code nucleo.job.failed} - counter, tagged by job type, error class</li>
 *   <li>{@code nucleo.job.duration} - timer, tagged by job type, outcome (completed/failed/timeout/cancelled)</li>
 *   <li>{@code nucleo.llm.calls} - counter, tagged by model</li>
 *   <li>{@code nucleo.llm.input_tokens} - counter, tagged by model</li>
 *   <li>{@code nucleo.llm.output_tokens} - counter, tagged by model</li>
 *   <li>{@code nucleo.llm.cache_read_tokens} - counter, tagged by model</li>
 *   <li>{@code nucleo.llm.cache_creation_tokens} - counter, tagged by model</li>
 *   <li>{@code nucleo.llm.latency} - timer, tagged by model</li>
 *   <li>{@code nucleo.limiter.acquire} - counter on {@code GRANTED_IMMEDIATE} and
 *       {@code GRANTED_FROM_HOLD}, tagged limiter, category, outcome ({@code immediate} or {@code held})</li>
 *   <li>{@code nucleo.limiter.wait} - timer on {@code GRANTED_FROM_HOLD}, and on {@code REJECTED}
 *       after a hold (a positive {@code waitNanos}), tagged limiter and category</li>
 *   <li>{@code nucleo.limiter.reject} - counter on {@code REJECTED}, tagged limiter, category, reason</li>
 * </ul>
 *
 * <p>A timeout counts as a failure with error class {@code timeout}, a cancellation as one with
 * {@code cancelled} and no LLM metrics; {@code HELD} and {@code RELEASED} record nothing. A
 * missing job type, model, limiter name, category or reason is tagged {@code unknown}; a null
 * token count records nothing; no job id ever becomes a tag, since its cardinality is unbounded.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-03-29)
 */
public class DefaultMeterCustomizer implements MeterCustomizer {

    @Override
    public void record(JobEvent event, MeterRegistry registry) {
        switch (event) {
            case JobStartedEvent e -> recordStarted(e, registry);
            case JobCompletedEvent<?> e -> recordCompleted(e, registry);
            case JobFailedEvent e -> recordFailed(e, registry);
            case JobTimedOut e -> recordTimedOut(e, registry);
            case JobCancelled e -> recordCancelled(e, registry);
            case LimiterEvent e -> recordLimiter(e, registry);
            default -> {}
        }
    }

    private void recordStarted(JobStartedEvent event, MeterRegistry registry) {
        String jobType = jobType(event);
        registry.counter("nucleo.job.started", "job.type", jobType).increment();
    }

    private void recordCompleted(JobCompletedEvent<?> event, MeterRegistry registry) {
        String jobType = jobType(event);
        registry.counter("nucleo.job.completed", "job.type", jobType).increment();
        recordDuration(event, registry, jobType, "completed");
        recordLlmMetrics(event, registry);
    }

    private void recordFailed(JobFailedEvent event, MeterRegistry registry) {
        String jobType = jobType(event);
        String errorClass = event.getError() != null ? event.getError().getClass().getSimpleName() : "unknown";
        registry.counter("nucleo.job.failed", "job.type", jobType, "error.class", errorClass).increment();
        recordDuration(event, registry, jobType, "failed");
        recordLlmMetrics(event, registry);
    }

    private void recordTimedOut(JobTimedOut event, MeterRegistry registry) {
        String jobType = jobType(event);
        registry.counter("nucleo.job.failed", "job.type", jobType, "error.class", "timeout").increment();
        recordDuration(event, registry, jobType, "timeout");
        recordLlmMetrics(event, registry);
    }

    private void recordCancelled(JobCancelled event, MeterRegistry registry) {
        String jobType = jobType(event);
        registry.counter("nucleo.job.failed", "job.type", jobType, "error.class", "cancelled").increment();
        recordDuration(event, registry, jobType, "cancelled");
    }

    private void recordDuration(AbstractTerminalEvent<?> event, MeterRegistry registry, String jobType, String outcome) {
        long durationMs = event.getDuration().toMillis();
        registry.timer("nucleo.job.duration", "job.type", jobType, "outcome", outcome)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    private void recordLlmMetrics(TerminalEvent event, MeterRegistry registry) {
        List<LLMResponse<?>> responses = event.getLlmResponses();
        if (responses == null || responses.isEmpty()) return;
        for (LLMResponse<?> r : responses) {
            String model = r.getModel() != null ? r.getModel() : "unknown";
            registry.counter("nucleo.llm.calls", "model", model).increment();
            if (r.getActualInputTokens() != null) {
                registry.counter("nucleo.llm.input_tokens", "model", model).increment(r.getActualInputTokens());
            }
            if (r.getActualOutputTokens() != null) {
                registry.counter("nucleo.llm.output_tokens", "model", model).increment(r.getActualOutputTokens());
            }
            if (r.getCacheReadInputTokens() != null) {
                registry.counter("nucleo.llm.cache_read_tokens", "model", model).increment(r.getCacheReadInputTokens());
            }
            if (r.getCacheCreationInputTokens() != null) {
                registry.counter("nucleo.llm.cache_creation_tokens", "model", model).increment(r.getCacheCreationInputTokens());
            }
            registry.timer("nucleo.llm.latency", "model", model)
                    .record(r.getLatencyMs(), TimeUnit.MILLISECONDS);
        }
    }

    private void recordLimiter(LimiterEvent event, MeterRegistry registry) {
        String limiter = event.limiterName() != null ? event.limiterName() : "unknown";
        String category = event.limiterCategory() != null ? event.limiterCategory() : "unknown";
        switch (event.type()) {
            case GRANTED_IMMEDIATE -> registry.counter("nucleo.limiter.acquire",
                    "limiter", limiter, "category", category, "outcome", "immediate").increment();
            case GRANTED_FROM_HOLD -> {
                registry.counter("nucleo.limiter.acquire",
                        "limiter", limiter, "category", category, "outcome", "held").increment();
                registry.timer("nucleo.limiter.wait",
                        "limiter", limiter, "category", category)
                        .record(event.waitNanos(), TimeUnit.NANOSECONDS);
            }
            case REJECTED -> {
                String reason = event.rejectReason() != null ? event.rejectReason() : "unknown";
                registry.counter("nucleo.limiter.reject",
                        "limiter", limiter, "category", category, "reason", reason).increment();
                if (event.waitNanos() > 0) {
                    registry.timer("nucleo.limiter.wait",
                            "limiter", limiter, "category", category)
                            .record(event.waitNanos(), TimeUnit.NANOSECONDS);
                }
            }
            case HELD, RELEASED -> { /* state transitions, no counter */ }
        }
    }

    private String jobType(JobEvent event) {
        JobSnapshot s = event.snapshot();
        if (s == null) return "unknown";
        String type = s.jobType();
        return type != null ? type : "unknown";
    }
}
