/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;




/**
 * Base interface for LLM reasoning capture.
 *
 * <p>Reasoning objects represent the thought process and decision-making rationale
 * that an LLM uses when generating responses. Different reasoning implementations
 * capture different aspects of the thinking process, from simple justifications
 * to complex chains of thought.
 *
 * <p>Reasoning is automatically serialized as part of the LLM response and stored
 * in the response JSON for audit and analysis purposes.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-21)
 * @see SimpleReasoning
 * @see ToolSelectionReasoning
 * @see ChainOfThoughtReasoning
 * @see AnalysisReasoning
 */
public interface Reasoning  {
    /**
     * Returns a user-friendly description of the reasoning.
     * This should provide a clean, readable summary without JSON formatting.
     *
     * @return human-readable reasoning description
     */
    String getUserFriendlyDescription();
}