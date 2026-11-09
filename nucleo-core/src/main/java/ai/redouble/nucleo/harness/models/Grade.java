/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import java.util.*;

/**
 * The capability ladder a seat declares its grade on. A spec's grade is its rung; a seat's
 * grade is the rung its work asks for, served by an entry of that rung when the deployment
 * can call one, else by the nearest rung above, else by the nearest below, with a warning in
 * the log each time the picker moves ({@link DefaultModelPicker}; the two fallbacks are
 * {@link ModelSettings#fallbackToStronger} and {@link ModelSettings#fallbackToWeaker}).
 *
 * <p>A model's rung is decided by its vendor's own tier and its generation, never by its
 * price: the nano, micro and lite tier is {@link #MICRO}; mini, small, haiku, flash and luna
 * {@link #SMALL}; medium, sonnet and terra {@link #MEDIUM}; large, and a flagship one generation
 * behind, {@link #LARGE}; the vendor's current flagship {@link #XL}; {@link #MEGA} above the
 * flagship. An open-weight model with no tier name goes by its parameters, the active ones
 * per token for a mixture of experts. The discovery's {@code GradeCriterion} applies the rule
 * when it writes an entry.
 *
 * <p>{@link #CEILING} is the one member that is not a rung: the declaration of a seat that
 * wants the strongest model this application may call wherever it runs - MEGA where the
 * deployment can call one, XL where it cannot. The picker serves it from {@link #MEGA}
 * downward; it is never a catalog entry's grade, never a pool, and has no rank for
 * {@link #atLeast}.
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
    /** The strongest model this application may call, served from {@link #MEGA} downward; not a rung. */
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
            throw new IllegalArgumentException("Grade.CEILING has no rank on the ladder; it asks for the strongest rung the picker can serve");
        }
        return this.ordinal() >= required.ordinal();
    }
}
