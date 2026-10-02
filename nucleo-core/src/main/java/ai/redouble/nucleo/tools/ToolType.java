/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools;

/**
 * Classification of tool computational type.
 * Used by {@link ToolWeight} to communicate cost signals to the LLM
 * so it can make proportional effort decisions.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-21)
 */
public enum ToolType {
    /**
     * No external calls - artifact lookups, in-memory computation
     */
    IN_MEMORY,
    /**
     * Database operations, lookup or write
     */
    DATABASE,
    /**
     * Single external API call
     */
    API_CALL,
    /**
     * LLM-driven agent with its own tool loop
     */
    THINKER

}
