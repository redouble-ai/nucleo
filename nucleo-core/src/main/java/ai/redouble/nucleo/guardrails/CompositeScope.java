/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.guardrails;

import java.util.*;

/**
 * A scope value spanning several UNRELATED axes at once - what a carrier returns from
 * {@link Scoped#scope()} when it claims two axes no inheritance chain connects (an
 * input that is both project- and user-scoped names both here). A refining axis is
 * not a composite's job: it extends its parent scope class and {@link Scope#pass}
 * compares the two at their shared level. As a candidate a composite is flattened by
 * {@link ScopeGuard#passAll} so each component is judged by the members that
 * recognize it; as a guard member it judges by delegating to its components.
 * Immutable; nested composites flatten on construction. Two composites are equal when
 * their component lists are equal in order, so a guard dedups them by value like any
 * other scope, and {@code toString} is {@code CompositeScope} followed by the list.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-18)
 */
public final class CompositeScope implements Scope {
    private final List<Scope> components;

    public CompositeScope(Scope... components) {
        this(Arrays.asList(components));
    }

    public CompositeScope(List<Scope> components) {
        List<Scope> flat = new ArrayList<>();
        for (Scope component : components) {
            if (component instanceof CompositeScope composite) {
                flat.addAll(composite.components);
            }
            else {
                flat.add(component);
            }
        }
        this.components = List.copyOf(flat);
    }

    public List<Scope> getComponents() {
        return components;
    }

    @Override
    public void pass(Scope candidate) throws GuardrailException {
        for (Scope component : components) {
            component.pass(candidate);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CompositeScope other && other.components.equals(this.components);
    }

    @Override
    public int hashCode() {
        return components.hashCode();
    }

    @Override
    public String toString() {
        return "CompositeScope" + components;
    }
}
