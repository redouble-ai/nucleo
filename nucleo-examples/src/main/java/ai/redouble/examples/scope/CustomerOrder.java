/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.scope;

import ai.redouble.nucleo.harness.schema.*;

/**
 * The customer-order tool's input, and a claim: implementing the marker makes the customer
 * named here the one the flow's binding judges, and the one the tool looks the order up
 * under.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
// region claim
public class CustomerOrder implements CustomerScoped {
    @LLMRequired
    @LLMDescription("The customer the order belongs to, like C-100")
    private String customerId;
    @LLMRequired
    @LLMDescription("The order number, like A-1042")
    private String orderNumber;
    // endregion

    public CustomerOrder() {
    }

    public CustomerOrder(String customerId, String orderNumber) {
        this.customerId = customerId;
        this.orderNumber = orderNumber;
    }

    @Override
    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public void setOrderNumber(String orderNumber) {
        this.orderNumber = orderNumber;
    }
}
