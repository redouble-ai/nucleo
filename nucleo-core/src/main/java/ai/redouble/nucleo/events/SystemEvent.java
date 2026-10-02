/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.events;

import ai.redouble.nucleo.harness.*;

/**
 * Sealed category for events that are not tied to any specific job
 * execution. These events have a null {@code snapshot()} and reach global and
 * by-type subscriptions for all jobs; workflow- and job-type-scoped
 * subscriptions do not match them.
 *
 * <p>Reserved for framework-internal state changes outside the job
 * lifecycle (scheduler lifecycle, system startup/shutdown, etc.).
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-14)
 */
public sealed interface SystemEvent extends JobEvent
        permits SchedulerEvent {
}
