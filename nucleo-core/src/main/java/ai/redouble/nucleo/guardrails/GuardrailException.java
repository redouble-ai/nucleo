/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.guardrails;

import ai.redouble.nucleo.harness.errors.*;

/**
 * Exception thrown when a guardrail validation fails.
 *
 * <p>This is a correctable exception - the LLM can adjust its approach based on
 * the guardrail violation and retry the operation successfully. The model reads it as
 * {@code Guardrail violation: } followed by the message the guard threw; a cause, when
 * one was given, is kept.</p>
 *
 * <p><strong>Examples:</strong></p>
 * <ul>
 *   <li>PII detected in tool input (LLM can redact and retry)</li>
 *   <li>Prohibited content in response (LLM can rephrase)</li>
 *   <li>Output format violations (LLM can restructure)</li>
 * </ul>
 *
 * @see CorrectableLLMException
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-10-08)
 */
public class GuardrailException extends CorrectableLLMException {

    /**
     * Create a guardrail exception.
     *
     * @param message the violation message
     */
    public GuardrailException(String message) {
        super(message);
    }

    /**
     * Create a guardrail exception with cause.
     *
     * @param message the violation message
     * @param cause the underlying cause
     */
    public GuardrailException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public String getLLMMessage() {
        return "Guardrail violation: " + getMessage();
    }
}
