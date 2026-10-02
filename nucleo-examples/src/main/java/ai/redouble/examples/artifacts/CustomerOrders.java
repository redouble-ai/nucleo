/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.artifacts;

import ai.redouble.nucleo.harness.schema.*;

import java.util.*;

/**
 * The customer-orders tool's output: one artifact per order.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
public class CustomerOrders {
    @LLMDescription("Every order of the customer, each an artifact with its reference")
    private List<OrderRecord> orders;

    public List<OrderRecord> getOrders() {
        return orders;
    }

    public void setOrders(List<OrderRecord> orders) {
        this.orders = orders;
    }
}
