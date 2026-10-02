/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors;

/**
 * Marker interface for any object that contains a message suitable for LLM consumption.
 * This is an audience marker - it declares that the implementing type can produce
 * a plain-text message formatted for an LLM to read and act upon.
 *
 * <p>This is NOT about data serialization. This is about containing a
 * human-language message directed at an LLM.</p>
 *
 * <p>Primary implementors:</p>
 * <ul>
 *   <li>{@link LLMReadableException} - exceptions that communicate errors to the LLM</li>
 * </ul>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-03-26)
 * @see HumanReadable
 * @see LLMReadableException
 */
public interface LLMReadable {
    /**
     * Returns a plain-text message suitable for LLM understanding.
     *
     * @return clean, actionable message for LLM
     */
    String getLLMMessage();
}
