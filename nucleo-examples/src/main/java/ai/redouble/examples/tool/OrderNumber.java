/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.tool;

import ai.redouble.nucleo.harness.schema.*;

/**
 * The order-status tool's input: the one parameter a model fills in when it calls the tool,
 * and a Java caller sets when it submits it.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
public class OrderNumber {
    @LLMRequired
    @LLMDescription("The order number, the letter A, a dash and four digits, like A-1042")
    private String orderNumber;

    public OrderNumber() {
    }

    public OrderNumber(String orderNumber) {
        this.orderNumber = orderNumber;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public void setOrderNumber(String orderNumber) {
        this.orderNumber = orderNumber;
    }
}
