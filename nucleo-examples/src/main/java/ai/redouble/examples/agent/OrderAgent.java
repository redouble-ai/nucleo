/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.agent;

import ai.redouble.examples.tool.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.prompt.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.thinking.*;

import java.util.*;

/**
 * An agent is a model working in a loop with tools: it reads the question, decides which
 * tool to call, reads what came back, and goes on until it can answer. Nobody wrote "look
 * up A-1002, then A-1005" - the order of the work is the model's decision, which is what an
 * agent is for. In Nucleo an agent is called a thinker; a {@link SingleObjectiveThinker}
 * works toward one final answer.
 *
 * <p>{@link ThinkerDeclaration} states what the agent needs from the model that serves it:
 * {@code Grade.SMALL} is the capability rung, {@code OutputSize.COMPACT} how long a single
 * turn may be (a tool call, or an answer with a handful of fields). Both are required, so an
 * agent without them does not compile.
 *
 * <p>{@code getSystemPromptText()} is the standing instructions the model reads before the
 * question; {@code @StaticPrompt} registers the text under a key so a deployment can replace
 * the wording without changing the class. {@code declareDefaultTools()} is the palette, the
 * tools the model may call, each shown to it with the name, description and input form the
 * tool class itself declares. The answer handler reads the final answer into an
 * {@link OrderAnswer}.
 *
 * <p>When the model calls the tool with a malformed number, the tool's typed refusal goes
 * back to it as the result of that call, marked as a mistake it can correct, and the model
 * calls again; nothing in this class handles it.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
// region agent
public class OrderAgent extends SingleObjectiveThinker<OrderQuestion, OrderAnswer> {
    public OrderAgent(Identifiable parent) {
        super(parent, new ThinkerDeclaration(Grade.SMALL, OutputSize.COMPACT));
        setAnswerHandler(new PojoResponseHandler<>(OrderAnswer.class));
    }

    @StaticPrompt
    @Override
    protected String getSystemPromptText() {
        return """
            You answer a customer's question about their orders. Look up every order the
            question is about with order_status before you answer, and state each status as
            the lookup returned it.
            """;
    }

    @Override
    protected List<Class<? extends Tool>> declareDefaultTools() {
        return List.of(OrderStatusTool.class);
    }
}
// endregion
