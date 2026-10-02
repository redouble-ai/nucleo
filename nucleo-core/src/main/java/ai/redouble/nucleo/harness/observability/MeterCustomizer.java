/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.observability;

import ai.redouble.nucleo.harness.*;
import io.micrometer.core.instrument.*;

/**
 * Callback for recording Micrometer metrics in {@link MicrometerObserver}.
 *
 * <p>Called on every event. Use pattern matching to decide what to record:
 * <pre>{@code
 * (event, registry) -> {
 *     switch (event) {
 *         case JobCompletedEvent<?> e -> registry.counter("job.completed",
 *                 "type", e.snapshot().jobType()).increment();
 *         case JobFailedEvent e -> registry.counter("job.failed",
 *                 "type", e.snapshot().jobType()).increment();
 *         default -> {}
 *     }
 * }
 * }</pre>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-03-29)
 */
@FunctionalInterface
public interface MeterCustomizer {

    /**
     * Called when an event is received. Record whatever metrics you need.
     *
     * @param event the job event
     * @param registry the meter registry to record metrics on
     */
    void record(JobEvent event, MeterRegistry registry);
}
