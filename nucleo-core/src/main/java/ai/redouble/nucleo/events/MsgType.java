/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.events;

/**
 * Message type for user-facing events.
 *
 * <p>Combines severity and lifecycle phase information for UI rendering.
 *
 * <p><b>Lifecycle Phases:</b>
 * <ul>
 *   <li>{@link #STARTING} - Job/operation beginning (0% progress)</li>
 *   <li>{@link #PROGRESSING} - In-progress updates (1-99% progress)</li>
 *   <li>{@link #COMPLETING} - Successful completion: 100% progress, a last stream chunk, a success notification, a completed exchange or workflow</li>
 *   <li>{@link #FAILING} - Error or failure</li>
 * </ul>
 *
 * <p><b>General-purpose types</b>, for unstructured job communication:
 * <ul>
 *   <li>{@link #STATUS_UPDATE} - Generic status update: a progress event without a percent, a retry signal</li>
 *   <li>{@link #MESSAGE} - General informational message: an info or warning notification</li>
 *   <li>{@link #ERROR} - Error message: an error notification</li>
 * </ul>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-10-11)
 */
public enum MsgType {
    // Lifecycle phases
    STARTING,
    PROGRESSING,
    COMPLETING,
    FAILING,

    // General-purpose types for unstructured job communication
    STATUS_UPDATE,
    MESSAGE,
    ERROR
}
