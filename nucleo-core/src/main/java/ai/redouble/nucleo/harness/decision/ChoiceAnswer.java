/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.decision;

import java.util.*;

/**
 * The answer to a {@link Choice}: the option that won, the probability of every option in the
 * order they were asked, and a confidence, which is arithmetic on that distribution (how far
 * the winner stands above a uniform spread) and not a second estimate of being right.
 *
 * @param choice        the winning option key, one of the keys asked
 * @param probabilities a probability per option key asked, in the order asked
 * @param confidence    the endpoint's concentration figure for the distribution, 0 to 1
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public record ChoiceAnswer(String choice, Map<String, Double> probabilities, double confidence) implements Answer {

    public ChoiceAnswer {
        if (choice == null || probabilities == null || probabilities.isEmpty()) {
            throw new IllegalArgumentException("A choice answer names its winner and every option's probability");
        }
        if (!probabilities.containsKey(choice)) {
            throw new IllegalArgumentException("The winning option '" + choice + "' is not among the options answered: " + probabilities.keySet());
        }
        LinkedHashMap<String, Double> kept = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : probabilities.entrySet()) {
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("Option '" + entry.getKey() + "' has no probability");
            }
            kept.put(entry.getKey(), Answer.requireProbability(entry.getValue(), "The probability of '" + entry.getKey() + "'"));
        }
        probabilities = Collections.unmodifiableMap(kept);
        Answer.requireProbability(confidence, "The confidence");
    }

    /** The probability of the winning option. */
    public double probability() {
        return probabilities.get(choice);
    }
}
