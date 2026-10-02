/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.artifacts;

import ai.redouble.nucleo.harness.schema.*;

/**
 * The customer-orders tool's input: whose orders to list.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
public class CustomerId {
    @LLMRequired
    @LLMDescription("The customer, the letter C, a dash and three digits, like C-100")
    private String customerId;

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }
}
