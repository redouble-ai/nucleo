/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.agent;

import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.thinking.*;

import java.util.*;

/**
 * What the order agent answers, as the Java object the caller declared. The model is shown
 * these fields - {@code @LLMDescription} tells it what belongs in each, {@code @LLMRequired}
 * that it must fill it - and a reply that does not fit, or leaves a required field empty,
 * goes back to the model to be corrected before the caller sees anything. So
 * {@code getReply()} and {@code getOrdersLookedUp()} hold values, never text to pick apart.
 *
 * <p>An agent's answer extends {@link ThinkerOutput}, which adds the model's account of how
 * it reached the answer, in the shape the type parameter names: {@link SimpleReasoning} is
 * one thought.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
// region answer
public class OrderAnswer extends ThinkerOutput<SimpleReasoning> {
    @LLMRequired
    @LLMDescription("The reply to the customer, stating each order's status as the lookup returned it")
    private String reply;
    @LLMRequired
    @LLMDescription("The numbers of the orders looked up to write the reply")
    private List<String> ordersLookedUp;
    // endregion

    public OrderAnswer() {
        setReasoning(new SimpleReasoning());
    }

    public String getReply() {
        return reply;
    }

    public void setReply(String reply) {
        this.reply = reply;
    }

    public List<String> getOrdersLookedUp() {
        return ordersLookedUp;
    }

    public void setOrdersLookedUp(List<String> ordersLookedUp) {
        this.ordersLookedUp = ordersLookedUp;
    }
}
