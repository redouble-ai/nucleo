/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.scopes;

import ai.redouble.nucleo.guardrails.*;

import java.util.*;

/**
 * The case axis: whose customer a piece of work is about. A class rather than a record,
 * because {@link OrderScope} narrows it by extending it and records cannot be extended.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-28)
 */
// region case
public class CaseScope implements Scope {
    final String customerId;

    public CaseScope(String customerId) {
        this.customerId = customerId;
    }

    @Override
    public boolean matches(Scope candidate) {
        return customerId.equals(((CaseScope) candidate).customerId);
    }
    // endregion

    @Override
    public boolean equals(Object o) {
        return o != null && getClass() == o.getClass() && customerId.equals(((CaseScope) o).customerId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getClass(), customerId);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + customerId + "]";
    }
}
