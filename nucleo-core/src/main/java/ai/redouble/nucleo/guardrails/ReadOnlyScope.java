/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.guardrails;

/**
 * The read-only binding, carried as a {@link Scope} so it travels exactly like every
 * other binding: composed into the effective guard at an orchestrator's dispatch,
 * sealed for its lifetime, and inherited by everything that flow submits. A delegate
 * or an agent-as-tool of a read-only flow is therefore read-only itself, transitively,
 * without any class arranging it.
 * <p>
 * It carries no value and judges no candidate - a flow either holds this binding or it
 * does not. What the binding MEANS is enforced where the palette is: a bound thinker
 * withholds mutating tools from its offer and refuses them at the call
 * ({@code ReadOnlyPalette}). Riding the guard is what makes the binding unforgeable
 * and inherited; the palette is what makes it bite.
 * <p>
 * Value-equal by type: two read-only bindings are the same binding, so merging guards
 * never accumulates duplicates.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-24)
 */
public final class ReadOnlyScope implements Scope {

    @Override
    public boolean equals(Object o) {
        return o instanceof ReadOnlyScope;
    }

    @Override
    public int hashCode() {
        return ReadOnlyScope.class.hashCode();
    }

    @Override
    public String toString() {
        return "ReadOnlyScope";
    }
}
