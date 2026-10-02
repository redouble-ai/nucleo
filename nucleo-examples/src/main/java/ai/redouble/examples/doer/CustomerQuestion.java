/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.doer;

/**
 * What the customer-report doer is handed: whose orders, and the question to answer over
 * all of them.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
public class CustomerQuestion {
    private String customerId;
    private String question;

    public CustomerQuestion() {
    }

    public CustomerQuestion(String customerId, String question) {
        this.customerId = customerId;
        this.question = question;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }
}
