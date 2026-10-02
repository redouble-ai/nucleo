/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.guardrails;

import ai.redouble.nucleo.harness.*;

/**
 * Base class for validation guardrails. Subclasses implement {@link #validate} and
 * {@link #targetType()}; everything else is inherited plumbing.
 *
 * @param <T> the answer type this guardrail validates
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-01)
 */
public abstract class AbstractValidationGuardrail<T> extends AbstractGuardrail<T> implements ValidationGuardrail<T> {
    protected AbstractValidationGuardrail(Identifiable parent) {
        super(parent);
    }

    @Override
    protected final String gateRungLabel() {
        return "VALIDATION";
    }
}
