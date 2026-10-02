/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.guardrails;

import ai.redouble.nucleo.harness.*;

/**
 * A guardrail: a job that validates a single target object at a tool-invocation edge.
 * <p>
 * The hierarchy is sealed into the four JOB-BASED kinds of the context ladder - each
 * consuming one more piece of framework-provided context than the previous:
 * <ol>
 *   <li>{@link AdmissionGuardrail} - no target; judges (principal, calling agent, tool)
 *       before a call exists</li>
 *   <li>{@link ContentGuardrail} - the target alone; a pure function of the payload</li>
 *   <li>{@link AuthGuardrail} - target plus the {@link JobSnapshot} (principal, tool,
 *       position in execution)</li>
 *   <li>{@link ValidationGuardrail} - target plus a live producer: judges a candidate
 *       final answer while the orchestrator that produced it still holds its
 *       conversation, so a refusal is fed back to that producer instead of failing its
 *       caller</li>
 * </ol>
 * The first three are declared by tools ({@link GuardedExecution}) and enforced by the
 * framework at the dispatch path for every execution route; the fourth is declared by
 * a producing orchestrator and enforced at its final-answer seat. SCOPE, which
 * additionally consumes what the flow is about, is not a guardrail job at all: scope is
 * a pure value judgment ({@link Scope}, {@link ScopeGuard}) enforced inline at the
 * submission chokepoint, where every child a flow creates is born - guardrail jobs
 * included - see {@link ScopeAuthority} and the dispatcher's submission rules.
 * <p>
 * A guardrail never receives a live framework object. Its complete evaluation context is
 * the target and the immutable {@link JobSnapshot} of the gated job. Everything else a
 * check needs is a lookup keyed by values found in those, performed by the guardrail's
 * own job (guardrails may declare resources).
 * <p>
 * Refusal is always {@link GuardrailException} - correctable, so the agent loop can act
 * on it. Infrastructure failures inside a guardrail propagate as themselves and fail the
 * gated job closed; they are never converted into refusals.
 *
 * @param <T> the type this guardrail validates
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-01)
 */
public sealed interface Guardrail<T> extends Job<Void> permits AuthGuardrail, ContentGuardrail, AdmissionGuardrail, ValidationGuardrail {
    /**
     * Validates the target object.
     * Throws {@link GuardrailException} if validation fails.
     *
     * @param target the object to validate
     * @throws GuardrailException if validation fails
     */
    void validate(T target) throws GuardrailException;

    /**
     * Set the target this guardrail will validate before it runs. Called by the
     * enforcing code immediately before submitting the guardrail as a job.
     */
    void setTarget(T target);

    /**
     * The class of targets this guardrail validates. Used by the enforcer to decide
     * applicability ({@code targetType().isInstance(target)}). Self-reported so the
     * framework never resolves it reflectively.
     */
    Class<T> targetType();

    /**
     * Delivers the immutable snapshot of the gated job before submission. The snapshot
     * is the guardrail's only window onto the invocation: principal ({@code userId}),
     * tool ({@code jobClass}), position ({@code jobId}, {@code parentJobId},
     * {@code workflowId}), attempt and timestamps.
     */
    void setGatedSnapshot(JobSnapshot snapshot);
}
