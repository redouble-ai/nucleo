/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.doer;

import ai.redouble.examples.tool.*;

import java.util.*;

/**
 * What the customer-report doer returns: every order it looked up, exactly as the tool
 * returned it, and the model's answer to the question over them.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
public class CustomerReport {
    private List<OrderStatus> orders;
    private String answer;

    public List<OrderStatus> getOrders() {
        return orders;
    }

    public void setOrders(List<OrderStatus> orders) {
        this.orders = orders;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }
}
