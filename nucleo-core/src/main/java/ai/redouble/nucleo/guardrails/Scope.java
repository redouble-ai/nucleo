/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.guardrails;

/**
 * A scope: the value that says what a flow is about - a ticket, a project, a tenant -
 * carried as a small axis-typed value produced by a {@link Scoped} carrier. A scope
 * judges candidates ITSELF: {@link #pass} throws when it recognizes the candidate's
 * type and clearly sees a violation; anything it does not recognize passes silently -
 * a scope constrains only what it speaks for. The thrown {@link GuardrailException}
 * is the refusal, its message the legible, LLM-actionable reason.
 * <p>
 * Recognition follows Java inheritance: a refining axis extends its parent axis
 * ({@code CityScope extends RegionScope}), and two scopes are related when
 * their class chains share any {@code Scope}-implementing class - parent vs child,
 * child vs parent, or two siblings under one parent. Related scopes are compared on
 * exactly the axes both sides carry, because each {@link #matches} level guards its
 * own cast and skips itself for a candidate that does not reach its depth. Only
 * genuinely unrelated types pass silently.
 * <p>
 * The comparison itself is {@link #matches}, whose default is value equality: a flat
 * per-axis scope is a pure record ({@code record TenantScope(String tenantId)
 * implements Scope {}}) and needs nothing else. A hierarchical axis is a plain
 * immutable class (records cannot extend) overriding {@code matches} at each level.
 * Richer semantics - sets, ranges - override {@link #pass} outright.
 * <p>
 * Implementations must be immutable: scopes are captured frozen at an authority's
 * dispatch and read by every check in its subtree.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-18)
 */
public interface Scope {
    /**
     * Judges a candidate scope value: returns silently when this scope does not
     * recognize the candidate's type or the candidate satisfies it; throws when it
     * recognizes the type and clearly sees a violation. Recognition is sharing any
     * {@code Scope}-implementing class in the two class chains; the comparison then
     * covers exactly the axes both sides carry.
     *
     * @throws GuardrailException the refusal, with the reason as its message
     */
    default void pass(Scope candidate) throws GuardrailException {
        if (sharesAxis(candidate) && !matches(candidate)) {
            throw new GuardrailException("Scope mismatch: this flow is bound to " + this
                    + " but the input names " + candidate);
        }
    }

    /**
     * Whether this scope and the candidate share an axis: any class in this scope's
     * chain that still implements {@code Scope} and the candidate is an instance of.
     * For flat scopes the chain is one class deep, so only same-type candidates are
     * recognized; a hierarchy recognizes ancestors, descendants, and siblings alike.
     */
    private boolean sharesAxis(Scope candidate) {
        for (Class<?> level = getClass(); Scope.class.isAssignableFrom(level); level = level.getSuperclass()) {
            if (level.isInstance(candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Compares the candidate on the axes BOTH sides carry. Called only with a
     * candidate that shares an axis with this scope, so the topmost level of the
     * chain casts freely; every deeper level guards its own cast with
     * {@code instanceof} and returns {@code super.matches} alone for a candidate
     * that does not reach its depth. The default - value equality - is right for
     * flat record scopes.
     */
    default boolean matches(Scope candidate) {
        return equals(candidate);
    }
}
