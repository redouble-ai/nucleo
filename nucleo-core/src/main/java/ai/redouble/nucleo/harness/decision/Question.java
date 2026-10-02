/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.decision;

/**
 * One typed question a decision model answers about a state. Exactly three shapes, sealed:
 * {@link Choice} (one of the options the caller names), {@link Noul} (the probability that a
 * statement is true) and {@link Score} (a position on an ordered scale the caller describes).
 * A question carries its instructions in words the model reads; the id it is asked under is
 * the caller's handle, keying the wire request and its answers, and is nothing the model is
 * asked about.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public sealed interface Question permits Choice, Noul, Score {

    /** What is being asked, in words the model reads; never blank. */
    String instructions();

    static String requireInstructions(String instructions) {
        if (instructions == null || instructions.isBlank()) {
            throw new IllegalArgumentException("A question needs its instructions: the words the model reads");
        }
        return instructions;
    }
}
