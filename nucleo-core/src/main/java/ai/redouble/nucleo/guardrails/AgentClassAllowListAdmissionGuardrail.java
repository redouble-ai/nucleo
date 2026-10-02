/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.guardrails;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;

import java.util.*;

/**
 * Reference {@link AdmissionGuardrail}: the declaring tool may only be reached through
 * the named agent classes. Demonstrates the admission rung's identity payload and is
 * genuinely useful for pinning a sensitive tool to the curated agents built around it:
 * <pre>{@code
 * @Override
 * public List<AdmissionGuardrail> declareAdmissionGuardrails() {
 *     return List.of(new AgentClassAllowListAdmissionGuardrail(this,
 *             Set.of("ai.redouble.app.rocket.thinkers.PaymentCalculationThinker")));
 * }
 * }</pre>
 * A caller directly under the workflow root has no agent class; such calls are refused
 * by this guardrail - reaching the tool without any agent is exactly what an allow-list
 * on agents forbids.
 * <p>
 * A missing {@link AdmissionContext} is a different thing entirely: the enforcer and
 * ToolHub always deliver one, so a null context means {@code init} was never called -
 * a code error of a caller running the guard by hand, thrown as
 * {@link UncorrectableRuntimeLLMException}, never as a refusal a model could act on.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-18)
 */
public class AgentClassAllowListAdmissionGuardrail extends AbstractAdmissionGuardrail {
    private final Set<String> allowedAgentClassNames;

    public AgentClassAllowListAdmissionGuardrail(Identifiable parent, Set<String> allowedAgentClassNames) {
        super(parent);
        this.allowedAgentClassNames = Set.copyOf(allowedAgentClassNames);
    }

    @Override
    protected void checkAdmission() throws GuardrailException {
        AdmissionContext ctx = getAdmissionContext();
        if (ctx == null) {
            throw new UncorrectableRuntimeLLMException("AgentClassAllowListAdmissionGuardrail ran with no AdmissionContext: "
                    + "init was never called. The enforcer and ToolHub always deliver the context, so this guard was run by hand.");
        }
        String caller = ctx.callerClassName();
        if (caller == null || !allowedAgentClassNames.contains(caller)) {
            throw new GuardrailException("Tool " + (ctx.toolClass() != null ? ctx.toolClass().getSimpleName() : "?")
                    + " is not available through " + (caller == null ? "a direct call" : caller)
                    + "; it is restricted to: " + allowedAgentClassNames);
        }
    }
}
