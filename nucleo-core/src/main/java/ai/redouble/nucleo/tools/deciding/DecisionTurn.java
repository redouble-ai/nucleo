/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.deciding;

import ai.redouble.nucleo.harness.decision.*;

import java.util.*;

/**
 * One turn of a {@link DecisionThinker}, as the run records it: what the model was offered,
 * what it chose and how surely, and what came of it. With no reasoning to read, the
 * distributions are the record of what the model thought, so every turn keeps them whole.
 *
 * @param turn      the turn's ordinal, from 1
 * @param next      the distribution over the tools offered and {@code finish}; null on a turn that had no move to offer
 * @param tool      the tool name chosen, or {@code finish}
 * @param argument  the distribution over the chosen tool's candidate artifacts, null on a finish
 * @param artifact  the ref of the artifact the tool ran on, null on a finish
 * @param result    the ref of the artifact the tool produced, null on a finish or a failure
 * @param failure   what the tool failed with, in the model's words, null otherwise
 * @param selection the probability, per artifact of the output type, that it belongs in the answer; kept as an
 *                  unmodifiable copy in the order asked, empty (never null) on a turn that put nothing to the model
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public record DecisionTurn(int turn, ChoiceAnswer next, String tool, ChoiceAnswer argument, String artifact, String result,
                           String failure, Map<String, Double> selection) {
    /** The name of the move that ends a run. */
    public static final String FINISH = "finish";

    public DecisionTurn {
        selection = selection == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(selection));
    }

    /** Whether this turn ended the run. */
    public boolean finished() {
        return FINISH.equals(tool);
    }
}
