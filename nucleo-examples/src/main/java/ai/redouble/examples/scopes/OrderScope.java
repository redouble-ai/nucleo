/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.scopes;

import ai.redouble.nucleo.guardrails.*;

import java.util.*;

/**
 * A narrowing of the case axis: one order of one customer. Extending {@link CaseScope} is
 * what relates the two, so a case binding judges an order claim and an order binding
 * judges a plain case claim, on the part both carry.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-28)
 */
// region order
public class OrderScope extends CaseScope {
    final String orderNumber;

    public OrderScope(String customerId, String orderNumber) {
        super(customerId);
        this.orderNumber = orderNumber;
    }

    @Override
    public boolean matches(Scope candidate) {
        if (!super.matches(candidate)) {
            return false;
        }
        // A plain case claim carries no order to judge; only a claim of this depth is narrowed
        return !(candidate instanceof OrderScope other) || orderNumber.equals(other.orderNumber);
    }
    // endregion

    @Override
    public boolean equals(Object o) {
        return super.equals(o) && orderNumber.equals(((OrderScope) o).orderNumber);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), orderNumber);
    }

    @Override
    public String toString() {
        return "OrderScope[" + customerId + "/" + orderNumber + "]";
    }
}
