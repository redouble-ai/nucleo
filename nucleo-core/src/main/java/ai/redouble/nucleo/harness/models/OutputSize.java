/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;


/**
 * The seat's answer-size vocabulary, the third word next to grade (which model) and depth
 * (how much effort): "MEDIUM model, STANDARD effort, COMPACT answer". A rung names the SHAPE
 * of what a turn returns, never a token count; the count is the catalog entry's translation
 * ({@link ModelSpec#getOutputBudget(OutputSize)}), the same way a depth translates to a
 * thinking budget. One rung per seat, sized to the largest turn the seat produces: a loop
 * turn may be a tool call or the final answer and the declaration covers both.
 *
 * <p>A seat whose answer fits no rung declares a raw token count instead
 * ({@link OutputDeclaration.Tokens}); that is the documented exception, never the habit.
 *
 * <p>{@code OutputSize.STANDARD} and {@code Depth.STANDARD} share a constant name on purpose.
 * Neither enum is ever statically imported, so every declaration site reads which STANDARD it
 * sets.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-06)
 */
public enum OutputSize {
    /** A label, a boolean, a classification, a one-field POJO. */
    VERDICT,
    /** A POJO with a handful of fields, a short list, a tool call with its arguments. */
    COMPACT,
    /** A rich POJO, a list of records, a table: the typical thinker final answer. */
    STANDARD,
    /** A written section, a report, a long extraction list. */
    EXTENDED,
    /** As much as the model can emit: the entry's output ceiling, not a number of ours. */
    MAX
}
