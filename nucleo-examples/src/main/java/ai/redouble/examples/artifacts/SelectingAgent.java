/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.artifacts;

import ai.redouble.examples.agent.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.prompt.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.thinking.*;

import java.util.*;

/**
 * An agent that selects data instead of retelling it: it lists a customer's orders with its
 * one tool and answers with the references of exactly the orders the question asks for. Its
 * answer class carries one field of its own, a one-sentence summary; the selection travels
 * on {@code ThinkerOutput}, whose references are looked up in the registry when the agent
 * finishes, so {@code getArtifacts()} returns the records the tool built. The summary is
 * the model's prose and may paraphrase; the records cannot.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
public class SelectingAgent extends SingleObjectiveThinker<OrderQuestion, SelectedOrders> {
    public SelectingAgent(Identifiable parent) {
        super(parent, new ThinkerDeclaration(Grade.SMALL, OutputSize.COMPACT));
        setAnswerHandler(new PojoResponseHandler<>(SelectedOrders.class));
    }

    @StaticPrompt
    @Override
    protected String getSystemPromptText() {
        return """
            You select orders. List the customer's orders with customer_orders, then answer
            with the artifact references of exactly the orders the question asks for.
            """;
    }

    @Override
    protected List<Class<? extends Tool>> declareDefaultTools() {
        return List.of(CustomerOrdersTool.class);
    }
}
