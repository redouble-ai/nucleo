/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

/**
 * The three client families a catalog entry can belong to, and a seat can ask for. A seat
 * of one kind resolves only to an entry of that kind: the gate refuses the rest, whichever
 * picker answered.
 *
 * <ul>
 *   <li>{@link #LLM}: a chat model on the capability ladder. Text in, text out, tools,
 *       thinking; every entry carries a {@link Grade}.</li>
 *   <li>{@link #EMBEDDINGS}: a vector model, pinned per corpus and frozen for the process,
 *       never on the ladder.</li>
 *   <li>{@link #DECISION}: a decision model in the shape of TypeSafe's Jev and its open
 *       replicas: a state plus typed questions in, a probability distribution over the
 *       options the caller declared out, and no text generated. Not on the ladder either:
 *       these models are ranked by a different trade, and a deployment names the one it
 *       serves.</li>
 * </ul>
 *
 * The kind of an entry follows the provider key the way the embeddings family always has
 * ({@link ModelSpec#kind()}): embeddings providers register under {@code *-embeddings}
 * keys, decision providers under {@code *-decision} keys, and everything else is an LLM.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public enum ModelKind {
    LLM,
    EMBEDDINGS,
    DECISION;

    /** The kind of every entry a provider key serves, by the registry's naming convention above. */
    public static ModelKind ofProviderKey(String providerKey) {
        if (providerKey.endsWith("-embeddings")) {
            return EMBEDDINGS;
        }
        if (providerKey.endsWith("-decision")) {
            return DECISION;
        }
        return LLM;
    }
}
