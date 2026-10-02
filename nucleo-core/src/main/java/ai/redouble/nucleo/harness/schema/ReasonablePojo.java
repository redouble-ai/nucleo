/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;


/**
 * Base class for LLM response POJOs that capture reasoning.
 *
 * <p>Extend this class when you want to capture the LLM's reasoning process
 * alongside the response data.
 * Subclasses should set a default reasoning type in their constructor based on
 * their typical use case, but this can be overridden by tools at runtime based
 * on context (e.g., debug mode, retry scenarios, high-stakes decisions).
 *
 * <p>The reasoning is automatically included in the LLM schema generation and
 * will be populated by the LLM as part of the response. It is stored as part
 * of the response JSON in the LLM run records for audit and analysis.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-21)
 * @see Reasoning
 */
public  abstract class ReasonablePojo<R extends Reasoning>  {
    @LLMDescription("The justification for this response, as a reader would check it")
    private R reasoning;

    public R getReasoning() {
        return reasoning;
    }

    public void setReasoning(R reasoning) {
        this.reasoning = reasoning;
    }
}