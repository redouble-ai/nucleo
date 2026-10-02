/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.scopes;

import ai.redouble.nucleo.guardrails.*;

/**
 * The order marker, extending the case marker the way its scope extends the case scope.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-28)
 */
// region ordermarker
public interface OrderScoped extends CaseScoped {
    String getOrderNumber();

    @Override
    default Scope scope() {
        return new OrderScope(getCustomerId(), getOrderNumber());
    }
}
// endregion
