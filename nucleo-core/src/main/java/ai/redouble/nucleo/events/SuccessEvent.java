/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.events;

/**
 * Sealed marker for successful job completion. Only {@link JobCompletedEvent} implements this.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-15)
 */
public sealed interface SuccessEvent extends TerminalEvent
        permits JobCompletedEvent {
    // Marker interface
}