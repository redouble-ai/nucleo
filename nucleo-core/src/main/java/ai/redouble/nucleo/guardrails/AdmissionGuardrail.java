/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.guardrails;

/**
 * Admission guardrail: decides whether this tool may be reached by this caller at all -
 * before any call exists, so it has no target. Its whole context is the immutable
 * {@link AdmissionContext}: the principal, the calling agent's class, the tool's class.
 * Capability-wide "never" belongs here, not to a per-call refusal.
 * <p>
 * Two evaluation points, one authority: at palette build (ToolHub) admission filters
 * what an agent is even offered - the model spends no tokens proposing refused calls -
 * and at dispatch the same guards are enforced on every route, so admission by a tool
 * that declares it ({@link GuardedExecution#declareAdmissionGuardrails()}) holds for
 * doer-direct submissions and every other path, not just catalog requests. The palette
 * consultation is the optimization; dispatch is the guarantee.
 * <p>
 * Reference implementation: {@link AgentClassAllowListAdmissionGuardrail}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-18)
 */
public non-sealed interface AdmissionGuardrail extends Guardrail<Void> {
    /**
     * Delivers the immutable admission context before submission. Called by the
     * enforcing code (ToolHub at palette build, the enforcer at dispatch).
     */
    void init(AdmissionContext context);
}
