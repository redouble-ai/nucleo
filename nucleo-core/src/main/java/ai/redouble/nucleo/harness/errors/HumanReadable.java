/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors;

/**
 * Marker interface for any object that contains a message suitable for human consumption.
 * This is an audience marker - it declares that the implementing type can produce
 * a plain-text message formatted for display to end users.
 *
 * <p>Primary implementors:</p>
 * <ul>
 *   <li>Events that should be shown in the UI (progress updates, notifications, errors)</li>
 * </ul>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-03-26)
 * @see LLMReadable
 */
public interface HumanReadable {
    /**
     * Returns a plain-text message suitable for display to end users.
     *
     * @return user-friendly message
     */
    String getHumanMessage();
}
