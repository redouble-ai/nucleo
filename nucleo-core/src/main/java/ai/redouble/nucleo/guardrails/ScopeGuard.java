/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.guardrails;

import java.util.*;

/**
 * The scope guard of an orchestrator: an immutable bag of bound {@link Scope} values.
 * {@link #passAll} runs every member against a candidate - AND-for-applicable emerges
 * from each scope ignoring what it does not recognize, so overlapping members (a
 * subtype axis and its supertype, two independent scopes on one flow) each get their
 * say and any one refusal blocks. {@link #merge} composes guards by member union,
 * deduplicated by value equality, so re-dispatching the same orchestrator (a doer
 * re-running a step) never grows the guard.
 * <p>
 * A guard sits as a field on every orchestrator: the authoring surface before
 * dispatch (explicit delegation assigns the parent's guard), sealed at dispatch when
 * the framework captures the effective guard - own field, own {@link Scoped#scope()}
 * if the orchestrator is scoped, and the caller's captured guard, merged.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-18)
 */
public final class ScopeGuard {
    private final List<Scope> scopes;

    public ScopeGuard(Scope... scopes) {
        this(Arrays.asList(scopes));
    }

    public ScopeGuard(List<Scope> scopes) {
        List<Scope> deduped = new ArrayList<>();
        for (Scope scope : scopes) {
            if (!deduped.contains(scope)) {
                deduped.add(scope);
            }
        }
        this.scopes = List.copyOf(deduped);
    }

    public List<Scope> getScopes() {
        return scopes;
    }

    /**
     * Composes this guard with another into a new guard judging by the union of both
     * member sets. Never mutates either side.
     */
    public ScopeGuard merge(ScopeGuard anotherScope) {
        List<Scope> union = new ArrayList<>(this.scopes);
        union.addAll(anotherScope.scopes);
        return new ScopeGuard(union);
    }

    /**
     * Judges a candidate scope value against every member. Composite candidates are
     * flattened so each component is judged by the members that recognize it.
     *
     * @throws GuardrailException the first member's refusal
     */
    public void passAll(Scope candidate) throws GuardrailException {
        if (candidate instanceof CompositeScope composite) {
            for (Scope component : composite.getComponents()) {
                passAll(component);
            }
            return;
        }
        for (Scope scope : scopes) {
            scope.pass(candidate);
        }
    }
}
