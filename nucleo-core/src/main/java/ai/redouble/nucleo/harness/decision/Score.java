/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.decision;

import java.util.*;

/**
 * A position on an ordered scale the caller describes, lowest level first. The answer is a
 * probability per level and their weighted position, so a state between two levels reads as
 * a fraction.
 *
 * @param instructions what is being rated
 * @param levels       the levels in order from lowest to highest, at least two, each described
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public record Score(String instructions, List<String> levels) implements Question {

    public Score {
        Question.requireInstructions(instructions);
        if (levels == null || levels.size() < 2) {
            throw new IllegalArgumentException("A score needs at least two levels, lowest first");
        }
        for (String level : levels) {
            if (level == null || level.isBlank()) {
                throw new IllegalArgumentException("Every level of a score is described");
            }
        }
        levels = List.copyOf(levels);
    }

    public static Score of(String instructions, String... levels) {
        return new Score(instructions, Arrays.asList(levels));
    }
}
