/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;


/**
 * What a seat declares about the size of its answer: an {@link OutputSize} rung, or a raw
 * token count for the seat whose answer fits no rung. One value type so every carrier (a
 * thinker, a conversation, a model binding) holds exactly one declaration by construction
 * instead of two fields kept mutually exclusive by discipline.
 *
 * <p>The declaration becomes a number only against a resolved spec: a rung is the entry's
 * translation, already within the entry's output ceiling; a count is the count, and the
 * resolution chain clamps it at that ceiling.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-06)
 */
public sealed interface OutputDeclaration permits OutputDeclaration.Rung, OutputDeclaration.Tokens {
    /** The output tokens this declaration asks for from the given entry. */
    int tokens(ModelSpec spec);

    static OutputDeclaration of(OutputSize size) {
        return new Rung(size);
    }

    static OutputDeclaration of(int tokens) {
        return new Tokens(tokens);
    }

    /** The vocabulary form: the entry translates the rung. */
    record Rung(OutputSize size) implements OutputDeclaration {
        public Rung {
            if (size == null) {
                throw new IllegalArgumentException("An output rung must name its size");
            }
        }

        @Override
        public int tokens(ModelSpec spec) {
            return spec.getOutputBudget(size);
        }

        @Override
        public String toString() {
            return size.name();
        }
    }

    /** The documented exception: a seat that knows its answer size in tokens. */
    record Tokens(int count) implements OutputDeclaration {
        public Tokens {
            if (count < 1) {
                throw new IllegalArgumentException("An output token declaration must be at least 1, got " + count);
            }
        }

        @Override
        public int tokens(ModelSpec spec) {
            return count;
        }

        @Override
        public String toString() {
            return count + " tokens";
        }
    }
}
