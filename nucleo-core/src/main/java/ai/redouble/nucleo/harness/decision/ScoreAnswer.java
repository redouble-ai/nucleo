/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.decision;

import java.util.*;

/**
 * The answer to a {@link Score}: a probability per level in the order the levels were asked,
 * their weighted position on the scale (level index 0 for the lowest, and fractional between
 * levels), and a confidence, which is arithmetic on the distribution and not a second
 * estimate of being right.
 *
 * @param score         the probability-weighted position, from 0 to the last level's index
 * @param levels        the level descriptions as asked, so a reader needs no question in hand
 * @param probabilities a probability per level, same order and size as the levels
 * @param confidence    the endpoint's concentration figure for the distribution, 0 to 1
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public record ScoreAnswer(double score, List<String> levels, List<Double> probabilities, double confidence) implements Answer {

    public ScoreAnswer {
        if (levels == null || probabilities == null || levels.size() < 2 || levels.size() != probabilities.size()) {
            throw new IllegalArgumentException("A score answer carries one probability per level, at least two levels");
        }
        for (int i = 0; i < probabilities.size(); i++) {
            if (probabilities.get(i) == null) {
                throw new IllegalArgumentException("Level " + i + " has no probability");
            }
            Answer.requireProbability(probabilities.get(i), "The probability of level " + i);
        }
        if (Double.isNaN(score) || score < 0.0 || score > levels.size() - 1) {
            throw new IllegalArgumentException("A score is a position between 0 and " + (levels.size() - 1) + ", got " + score);
        }
        levels = List.copyOf(levels);
        probabilities = List.copyOf(probabilities);
        Answer.requireProbability(confidence, "The confidence");
    }

    /** The index of the most probable level. */
    public int topLevel() {
        int top = 0;
        for (int i = 1; i < probabilities.size(); i++) {
            if (probabilities.get(i) > probabilities.get(top)) {
                top = i;
            }
        }
        return top;
    }
}
