/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.events;

import ai.redouble.nucleo.harness.*;

/**
 * Sealed category for progress-style events emitted during job execution:
 * intermediate status updates, streaming content chunks, and similar
 * continuous-or-incremental signals intended for user-visible progress
 * reporting.
 *
 * <p>The single permit is {@link JobProgressEvent}, which is deliberately
 * left non-sealed so it can host parametric payload subclasses like
 * {@link ContentStreamEvent}. New progress varieties should extend
 * {@code JobProgressEvent<P>} and inherit category membership automatically.
 *
 * <p>Broad subscribers that do not care about fine-grained progress (console
 * loggers, DB persisters) simply do not subscribe to this category.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-14)
 */
public sealed interface ProgressEvent extends JobEvent
        permits JobProgressEvent {
}
