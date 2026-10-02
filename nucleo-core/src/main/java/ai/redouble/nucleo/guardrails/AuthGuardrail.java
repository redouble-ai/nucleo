/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.guardrails;

/**
 * Authorization guardrail: judges whether the principal may perform this invocation.
 * Consumes the target plus the gated job's {@link ai.redouble.nucleo.harness.JobSnapshot}
 * (the principal is {@code snapshot.getUserId()}); consults durable state - rights,
 * grants, meters - by its own lookups, keyed by values found in the target and the
 * snapshot. A cap or budget check is an authorization guardrail whose lookup consults
 * consumption state; budget is not a separate kind.
 * <p>
 * The verdict of an authorization check is invariant across flows: "may this principal
 * do this to this resource" answers the same everywhere, which is why the seat is the
 * TOOL ({@link GuardedExecution#declareAuthGuardrails()}) and enforcement covers every
 * route. Input-side by nature: an authorization refusal must precede the act.
 * <p>
 * Reference implementation: {@link PrincipalAllowListGuardrail}.
 *
 * @param <T> the type this guardrail validates
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-18)
 */
public non-sealed interface AuthGuardrail<T> extends Guardrail<T> {
}
