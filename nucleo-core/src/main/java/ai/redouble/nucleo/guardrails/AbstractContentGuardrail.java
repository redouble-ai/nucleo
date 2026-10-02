/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.guardrails;

import ai.redouble.nucleo.harness.*;

/**
 * Base class for content guardrails. Subclasses implement {@link #validate},
 * {@link #targetType()} and {@link #direction()}; everything else is inherited
 * plumbing.
 *
 * @param <T> the type this guardrail validates
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-18)
 */
public abstract class AbstractContentGuardrail<T> extends AbstractGuardrail<T> implements ContentGuardrail<T> {
    protected AbstractContentGuardrail(Identifiable parent) {
        super(parent);
    }

    @Override
    protected final String gateRungLabel() {
        return "CONTENT_" + direction().name();
    }
}
