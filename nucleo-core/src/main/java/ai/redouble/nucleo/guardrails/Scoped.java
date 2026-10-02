/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.guardrails;

/**
 * Something that produces its scope value - the parent of every domain marker
 * (TicketScoped, ProjectScoped, ClaimScoped, ...). Implementing a marker IS the entire
 * scope declaration: an orchestrator implementing one becomes a scope authority whose
 * value is captured frozen at its dispatch; an input implementing one carries the claim
 * every enforcement point judges against the flow's guard.
 * <p>
 * Deliberately not generic: a carrier implementing two axis markers inherits two
 * conflicting defaults, and the compiler forces it to override {@link #scope()} with a
 * {@link CompositeScope} naming both values - multi-axis carriers state their axes
 * explicitly, enforced by javac rather than convention.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-18)
 */
public interface Scoped {

    Scope scope();
}
