/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.observability;

import ai.redouble.nucleo.harness.*;
import io.opentelemetry.api.trace.*;

/**
 * Callback for customizing OpenTelemetry spans in {@link OpenTelemetryObserver}.
 *
 * <p>Called on every event that has an active span. Use pattern matching to
 * decide what to do:
 * <pre>{@code
 * (event, span) -> {
 *     switch (event) {
 *         case JobStartedEvent e -> span.setAttribute("user.id", e.snapshot().getUserId());
 *         case JobCompletedEvent<?> e -> span.addEvent("completed");
 *         case JobFailedEvent e -> span.setAttribute("error.type", e.getError().getClass().getName());
 *         case JobProgressEvent<?> e -> span.addEvent(e.message());
 *         default -> {}
 *     }
 * }
 * }</pre>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-03-29)
 */
@FunctionalInterface
public interface SpanCustomizer {

    /**
     * Called when an event is received for a job that has an active span.
     *
     * @param event the job event
     * @param span the span associated with this job
     */
    void customize(JobEvent event, Span span);
}
