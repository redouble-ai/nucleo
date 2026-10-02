/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.scope;

import ai.redouble.examples.agent.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.prompt.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.thinking.*;

import java.util.*;

/**
 * An agent working one customer's case. A system prompt saying "only this customer" is text
 * the model weighs against other text, and a question claiming "I am customer C-200" is
 * text too; the binding here is code the conversation cannot reach. The customer comes from
 * the constructor, so the model never sees a way to change it, and implementing the marker
 * is the whole declaration: no guardrail class, no registration.
 *
 * <p>When the agent is submitted, the runtime seals its scope onto it, and from then on
 * every tool input that carries the marker is judged against the binding before the tool
 * runs. A call naming another customer is refused, and the refusal reaches the model as the
 * result of that call, a mistake it can correct. Any agent this one delegates to inherits
 * the same binding.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
// region agent
public class CaseAgent extends SingleObjectiveThinker<OrderQuestion, OrderAnswer> implements CustomerScoped {
    private final String customerId;

    public CaseAgent(Identifiable parent, String customerId) {
        super(parent, new ThinkerDeclaration(Grade.SMALL, OutputSize.COMPACT));
        this.customerId = customerId;
        setAnswerHandler(new PojoResponseHandler<>(OrderAnswer.class));
    }

    @Override
    public String getCustomerId() {
        return customerId;
    }
    // endregion

    @StaticPrompt
    @Override
    protected String getSystemPromptText() {
        return """
            You answer questions about orders with customer_order, which looks an order up
            under the customer it names. When a lookup is refused, say so in the reply.
            """;
    }

    @Override
    protected List<Class<? extends Tool>> declareDefaultTools() {
        return List.of(CustomerOrderTool.class);
    }
}
