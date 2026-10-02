/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.events;

import ai.redouble.nucleo.harness.*;

/**
 * Sealed marker for events fired exclusively by the platform (job dispatcher).
 *
 * <p>User code cannot create custom lifecycle events. All implementations are
 * final or sealed within this hierarchy. This guarantees that observability
 * infrastructure (persistence, metrics, tracing) sees only well-defined
 * state transitions.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-08)
 */
public sealed interface LifecycleEvent extends JobEvent
        permits JobScheduled, JobStartedEvent, TerminalEvent,
                OrchestratorResumedEvent, OrchestratorIdleEvent {
}
