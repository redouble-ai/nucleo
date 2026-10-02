/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.decision;

/**
 * What a decision model answered to one {@link Question}: a distribution over the options
 * the caller declared, in the shape of the question asked. Sealed to the three question
 * shapes, so a caller reads an answer by the type it asked for and nothing else can come
 * back. A probability here is the model's, as the endpoint reported it; it is a number the
 * caller thresholds, never a measured rate of being right.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public sealed interface Answer permits ChoiceAnswer, NoulAnswer, ScoreAnswer {

    static double requireProbability(double value, String what) {
        if (Double.isNaN(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(what + " is a probability between 0 and 1, got " + value);
        }
        return value;
    }
}
