/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.benchmark;

/**
 * A score in code, for an output that can be compared to the reference without a model:
 * an extraction against the reference extraction, a classification against the reference
 * class. Returns a number in [0, 1], or null when the pair cannot be compared. The judge is
 * the score for everything else; a benchmark may run both.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-17)
 */
@FunctionalInterface
public interface Scorer<O> {
    Double score(O reference, O candidate);
}
