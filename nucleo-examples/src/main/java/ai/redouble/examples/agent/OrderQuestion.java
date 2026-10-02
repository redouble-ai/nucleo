/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.agent;

import ai.redouble.nucleo.tools.thinking.*;

/**
 * What the order agent is asked: a customer's question, in its words, as the query.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
public class OrderQuestion extends ThinkerInput {
    public OrderQuestion() {
    }

    public OrderQuestion(String question) {
        setQuery(question);
    }
}
