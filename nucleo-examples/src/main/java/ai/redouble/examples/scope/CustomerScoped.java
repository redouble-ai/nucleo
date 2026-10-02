/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.scope;

import ai.redouble.nucleo.guardrails.*;

/**
 * The customer marker. On an agent it is the binding, set by code when the agent is built;
 * on a tool input it is the claim, written by the model on every call and judged against
 * the binding before the tool runs.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
// region marker
public interface CustomerScoped extends Scoped {
    String getCustomerId();

    @Override
    default Scope scope() {
        return new CustomerScope(getCustomerId());
    }
}
// endregion
