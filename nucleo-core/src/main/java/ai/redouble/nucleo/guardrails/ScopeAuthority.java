/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.guardrails;

/**
 * The scope-carrying surface of every orchestrator: a {@link ScopeGuard} field that is
 * the authoring seat before dispatch and a sealed capture afterwards.
 * <p>
 * Before dispatch, trusted code may assign the field - the explicit-delegation case
 * (handing a parent's guard to a sub-agent constructed outside its lineage). At the
 * orchestrator's dispatch the framework composes the effective guard ONCE - the field,
 * plus the orchestrator's own {@link Scoped#scope()} when it is scoped, plus the
 * calling orchestrator's captured guard - and seals it via {@link #sealScopeGuard}.
 * From then on {@link #setScopeGuard} throws and {@link #getScopeGuard()} returns the
 * captured effective guard, so delegation always copies the real thing: "once set, the
 * scope cannot be changed by model output" - or by anything else - holds by the
 * absence of a mutation surface.
 * <p>
 * Also the submission-authority marker: only jobs implementing this interface may
 * submit other jobs from within their execution. A guardrail may declare it too: the
 * internal door composes and seals the guard from the flow the gated job was admitted
 * under, so a judge the guard spawns inherits that flow's binding like any child.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-18)
 */
public interface ScopeAuthority {
    /**
     * The guard: the authored field before dispatch, the sealed effective guard after.
     */
    ScopeGuard getScopeGuard();

    /**
     * Assigns the guard. On an orchestrator, legal only before dispatch: once sealed it
     * throws {@link IllegalStateException}. On a guardrail that declares this seat it
     * always throws {@link UnsupportedOperationException}: a guard's binding is the gated
     * flow's, composed and sealed by the internal door, never authored.
     */
    void setScopeGuard(ScopeGuard guard);

    /**
     * Framework-called at dispatch: fixes the effective guard for this job's lifetime.
     */
    void sealScopeGuard(ScopeGuard effective);
}
