/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.scopes;

import ai.redouble.nucleo.guardrails.*;
import ai.redouble.nucleo.harness.schema.*;

/**
 * The note tool's input, claiming two unrelated axes at once: the order (which carries the
 * case) and the channel. Two markers means two default {@code scope()} methods, so the
 * compiler forces the override, and the composite is how one claim answers for both.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-28)
 */
// region claim
public class FileNote implements OrderScoped, ChannelScoped {
    @LLMRequired
    @LLMDescription("The customer the note is about, like C-100")
    private String customerId;
    @LLMRequired
    @LLMDescription("The order the note is about, like A-1042")
    private String orderNumber;
    @LLMRequired
    @LLMDescription("The channel the exchange happened on: email or phone")
    private String channel;
    @LLMRequired
    @LLMDescription("The note to file")
    private String note;

    @Override
    public Scope scope() {
        return new CompositeScope(OrderScoped.super.scope(), ChannelScoped.super.scope());
    }
    // endregion

    public FileNote() {
    }

    public FileNote(String customerId, String orderNumber, String channel, String note) {
        this.customerId = customerId;
        this.orderNumber = orderNumber;
        this.channel = channel;
        this.note = note;
    }

    @Override
    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    @Override
    public String getOrderNumber() {
        return orderNumber;
    }

    public void setOrderNumber(String orderNumber) {
        this.orderNumber = orderNumber;
    }

    @Override
    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
