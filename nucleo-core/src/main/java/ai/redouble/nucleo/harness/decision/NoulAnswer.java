/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.decision;

/**
 * The answer to a {@link Noul}: one number, the probability that the statement is true. It
 * carries no separate confidence, because it already is the model's belief; a value near one
 * half is the model saying it has nothing to go on.
 *
 * @param probability the probability of yes, 0 to 1
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public record NoulAnswer(double probability) implements Answer {

    public NoulAnswer {
        Answer.requireProbability(probability, "A noul's probability");
    }
}
