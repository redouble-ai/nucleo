/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.guardrails;

import java.util.*;

/**
 * A job that declares its own guardrails. Implemented by tools; consulted by the
 * framework at every dispatch, on every route (thinker-driven, doer-direct, fan-out,
 * recovery, external serving) - the declaration travels with the tool, so protection
 * stops being a property of the route a call took.
 * <p>
 * The three methods are the tool-side declaration seats of the context ladder:
 * content and auth are consulted per invocation, after the input is set, so a tool may
 * decide case by case with its own logic and construct parameterized instances
 * (each returned instance is single-use - fresh per call). Admission is consulted both
 * at palette build and at dispatch, and exists before any call: it MUST NOT read the
 * input.
 * <p>
 * Scope cannot be declared here - it has no meaning on a leaf tool. Orchestrators
 * declare scope via {@link ScopeAuthority}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-18)
 */
public interface GuardedExecution {
    /**
     * The object input-phase guardrails validate - the tool's typed input. Read by the
     * enforcer after dependency resolution, before resource allocation.
     */
    Object guardedInputTarget();

    /**
     * Content guardrails this invocation declares, constructed fresh per call
     * (parent = this job). Direction is each guard's own property: INPUT guards run
     * against {@link #guardedInputTarget()}, OUTPUT guards against the result.
     */
    default List<ContentGuardrail<?>> declareContentGuardrails() {
        return List.of();
    }

    /**
     * Authorization guardrails this invocation declares, constructed fresh per call
     * (parent = this job). Input-side by nature.
     */
    default List<AuthGuardrail<?>> declareAuthGuardrails() {
        return List.of();
    }

    /**
     * Admission guardrails this tool declares. Contractually input-free: this method is
     * also called at palette build, where no input exists.
     */
    default List<AdmissionGuardrail> declareAdmissionGuardrails() {
        return List.of();
    }
}
