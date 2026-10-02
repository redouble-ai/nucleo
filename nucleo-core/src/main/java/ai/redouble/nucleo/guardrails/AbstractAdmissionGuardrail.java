/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.guardrails;

import ai.redouble.nucleo.harness.*;
import org.slf4j.*;

/**
 * Base class for admission guardrails. Subclasses implement {@link #checkAdmission}
 * against the immutable {@link AdmissionContext} - no live framework object is ever
 * delivered. Consulted at palette build (so refused tools are never offered) and
 * enforced at dispatch on every route (so refusal holds regardless of how the tool is
 * reached).
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-03-14)
 */
public abstract class AbstractAdmissionGuardrail extends AbstractGuardrail<Void> implements AdmissionGuardrail {
    private static final Logger log = LoggerFactory.getLogger(AbstractAdmissionGuardrail.class);
    private AdmissionContext admissionContext;

    protected AbstractAdmissionGuardrail(Identifiable parent) {
        super(parent);
    }

    @Override
    public void init(AdmissionContext context) {
        this.admissionContext = context;
    }

    protected AdmissionContext getAdmissionContext() {
        return admissionContext;
    }

    /**
     * Check whether the tool should be admitted for this caller.
     * Throw {@link GuardrailException} to deny admission. The enforcing code delivers
     * the {@link AdmissionContext} before validation on every route, so a null context
     * here is a code error of a caller running the guard by hand, never a denial.
     *
     * @throws GuardrailException if admission is denied, with a reason the LLM can understand
     */
    protected abstract void checkAdmission() throws GuardrailException;

    @Override
    public final void validate(Void target) throws GuardrailException {
        checkAdmission();
    }

    @Override
    public Class<Void> targetType() {
        return Void.class;
    }

    /**
     * Records who reached what through whom ({@code principal via caller -> Tool}) under
     * {@link #OBS_TARGET}, since an admission guard has no target to record.
     */
    @Override
    public void preExecute(JobContext<Void> context) {
        super.preExecute(context);
        try {
            if (admissionContext != null) {
                context.putMetadata(OBS_TARGET, admissionContext.principal() + " via "
                        + admissionContext.callerClassName() + " -> "
                        + (admissionContext.toolClass() != null ? admissionContext.toolClass().getSimpleName() : "?"));
            }
        }
        catch (Exception e) {
            log.warn("Failed to record admission guardrail context for observability: {}", e.getMessage());
        }
    }

    @Override
    protected final String gateRungLabel() {
        return "ADMISSION";
    }
}
