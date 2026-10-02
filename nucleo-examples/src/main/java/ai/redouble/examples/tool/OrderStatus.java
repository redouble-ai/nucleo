/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.tool;

import ai.redouble.nucleo.harness.schema.*;

/**
 * One order as the order-status tool returns it. A plain Java bean: the runtime renders its
 * fields and their descriptions for the model, and the tool's caller gets the object itself.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
public class OrderStatus {
    @LLMDescription("The order number, the letter A, a dash and four digits")
    private String orderNumber;
    @LLMDescription("The customer the order belongs to")
    private String customerId;
    @LLMDescription("PROCESSING, SHIPPED, DELIVERED or DELAYED")
    private String status;
    @LLMDescription("The date the order was promised for, as YYYY-MM-DD")
    private String promisedFor;
    @LLMDescription("What the warehouse or the carrier says about it")
    private String note;

    public String getOrderNumber() {
        return orderNumber;
    }

    public void setOrderNumber(String orderNumber) {
        this.orderNumber = orderNumber;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPromisedFor() {
        return promisedFor;
    }

    public void setPromisedFor(String promisedFor) {
        this.promisedFor = promisedFor;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    @Override
    public String toString() {
        return orderNumber + " (" + customerId + "): " + status + ", promised for " + promisedFor + ", " + note;
    }
}
