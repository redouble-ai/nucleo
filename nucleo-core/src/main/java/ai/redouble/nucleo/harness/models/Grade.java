/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import java.util.*;

/**
 * The capability ladder a seat declares its floor on. A spec's grade is its rung; a seat's
 * grade is the lowest rung that can do its work, and the resolution gate refuses a spec
 * below it while accepting an over-qualified one.
 *
 * <p>A model's rung is decided by its vendor's own tier and its generation, never by its
 * price: the nano, micro and lite tier is {@link #MICRO}; mini, small, haiku, flash and luna
 * {@link #SMALL}; medium, sonnet and terra {@link #MEDIUM}; large, and a flagship one generation
 * behind, {@link #LARGE}; the vendor's current flagship {@link #XL}; {@link #MEGA} above the
 * flagship. An open-weight model with no tier name goes by its parameters, the active ones
 * per token for a mixture of experts. The discovery's {@code GradeCriterion} applies the rule
 * when it writes an entry.
 *
 * <p>{@link #CEILING} is the one member that is not a rung: it means "the strongest grade
 * this deployment serves" ({@code ModelPicker.ceiling()}), the declaration of a seat that
 * wants the best model available wherever it runs - MEGA where the picker pins one, XL
 * where it does not. It is translated to that rung once, at the picker gate, before any
 * picker or comparison sees it; it is never a catalog entry's grade and never a pool.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-27)
 */
public enum Grade {
    MICRO,
    SMALL,
    MEDIUM,
    LARGE,
    XL,
    MEGA,
    /** The deployment's strongest served grade, resolved at the picker gate; not a rung. */
    CEILING;

    private static final List<Grade> RUNGS = List.of(MICRO, SMALL, MEDIUM, LARGE, XL, MEGA);

    /** The ladder proper, lowest first: every member that names a rung a spec can carry. */
    public static List<Grade> rungs() {
        return RUNGS;
    }

    public boolean isRung() {
        return this != CEILING;
    }

    /** Ladder comparison between two rungs; {@link #CEILING} has no rank and refuses. */
    public boolean atLeast(Grade required) {
        if (!isRung() || !required.isRung()) {
            throw new IllegalArgumentException("Grade.CEILING has no rank on the ladder; it is resolved at the picker gate before comparison");
        }
        return this.ordinal() >= required.ordinal();
    }
}
