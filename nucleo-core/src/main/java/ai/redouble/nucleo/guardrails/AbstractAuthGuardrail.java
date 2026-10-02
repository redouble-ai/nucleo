/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.guardrails;

import ai.redouble.nucleo.harness.*;

/**
 * Base class for authorization guardrails. Subclasses implement {@link #validate} and
 * {@link #targetType()}; the principal is read from {@link #getGatedSnapshot()} and
 * durable state is reachable through {@link #getResources()} when the subclass declares
 * a provider in its requirements.
 *
 * @param <T> the type this guardrail validates
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-18)
 */
public abstract class AbstractAuthGuardrail<T> extends AbstractGuardrail<T> implements AuthGuardrail<T> {
    protected AbstractAuthGuardrail(Identifiable parent) {
        super(parent);
    }

    /**
     * The principal on whose behalf the gated invocation runs, from the gated job's
     * snapshot.
     */
    protected String getPrincipal() {
        JobSnapshot snapshot = getGatedSnapshot();
        return snapshot != null ? snapshot.getUserId() : null;
    }

    @Override
    protected final String gateRungLabel() {
        return "AUTH";
    }
}
