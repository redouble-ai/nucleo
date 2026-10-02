/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.guardrails;

import ai.redouble.nucleo.harness.*;

import java.util.*;

/**
 * Reference {@link AuthGuardrail}: only the named principals may invoke the declaring
 * tool. Demonstrates the auth rung's contract - the principal arrives on the gated
 * job's snapshot, never as a live object - and is genuinely useful for restricting
 * system tools to service principals:
 * <pre>{@code
 * @Override
 * public List<AuthGuardrail<?>> declareAuthGuardrails() {
 *     return List.of(new PrincipalAllowListGuardrail(this, Set.of("ops-oncall", "release-manager")));
 * }
 * }</pre>
 * A principal outside the set is refused, a missing principal included; the refusal
 * names the principal, the gated job's class and the allowed principals.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-18)
 */
public class PrincipalAllowListGuardrail extends AbstractAuthGuardrail<Object> {
    private final Set<String> allowedPrincipals;

    public PrincipalAllowListGuardrail(Identifiable parent, Set<String> allowedPrincipals) {
        super(parent);
        this.allowedPrincipals = Set.copyOf(allowedPrincipals);
    }

    @Override
    public Class<Object> targetType() {
        return Object.class;
    }

    @Override
    public void validate(Object target) throws GuardrailException {
        String principal = getPrincipal();
        if (principal == null || !allowedPrincipals.contains(principal)) {
            throw new GuardrailException("Principal " + principal + " is not authorized to invoke "
                    + gatedToolName() + "; authorized principals: " + allowedPrincipals);
        }
    }

    private String gatedToolName() {
        JobSnapshot snapshot = getGatedSnapshot();
        return snapshot != null ? snapshot.jobClass().getSimpleName() : "this tool";
    }
}
