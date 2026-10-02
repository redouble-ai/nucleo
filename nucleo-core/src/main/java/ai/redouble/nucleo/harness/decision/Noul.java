/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.decision;

/**
 * The probability that a statement is true. The instructions are the statement, written so
 * that it is testable; the two descriptions, when given, say what a yes and a no mean, and
 * come together or not at all.
 *
 * @param instructions the statement to judge
 * @param whenTrue     what a yes means, or null with {@code whenFalse} null
 * @param whenFalse    what a no means, or null with {@code whenTrue} null
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public record Noul(String instructions, String whenTrue, String whenFalse) implements Question {

    public Noul {
        Question.requireInstructions(instructions);
        if ((whenTrue == null) != (whenFalse == null)) {
            throw new IllegalArgumentException("A noul describes both a yes and a no, or neither");
        }
    }

    /** The statement alone. */
    public static Noul of(String instructions) {
        return new Noul(instructions, null, null);
    }
}
