/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.scopes;

import ai.redouble.nucleo.guardrails.*;

/**
 * The case marker: on an orchestrator it binds the flow to one customer, on an input it is
 * the claim the binding judges.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-28)
 */
public interface CaseScoped extends Scoped {
    String getCustomerId();

    @Override
    default Scope scope() {
        return new CaseScope(getCustomerId());
    }
}
