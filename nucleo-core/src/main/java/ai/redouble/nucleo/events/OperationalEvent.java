/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.events;

import ai.redouble.nucleo.harness.*;

/**
 * Sealed category for high-volume operational telemetry: rate limiter
 * transitions, backpressure signals, and similar framework-internal
 * observability events that fire at runtime-load frequencies rather than
 * at job-lifecycle frequencies.
 *
 * <p>Events in this category are expected to land in the thousands per
 * second under real workloads. Broad subscribers (console loggers, DB
 * persisters) do not subscribe to this category. Observability bridges
 * (OpenTelemetry, Micrometer) do, and handle the volume via cheap
 * per-event work (meter updates, span events).
 *
 * <p>New operational event types require a permits update on this
 * interface and an explicit handler in any subscribing observer.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-14)
 */
public sealed interface OperationalEvent extends JobEvent
        permits LimiterEvent {
}
