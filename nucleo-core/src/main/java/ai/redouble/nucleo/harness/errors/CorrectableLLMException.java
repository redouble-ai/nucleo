/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors;

import ai.redouble.nucleo.guardrails.*;

/**
 * Base class for exceptions where the LLM's input was wrong and it <em>may</em> fix its
 * parameters and call the same tool again.
 *
 * <p>The key semantic: the tool works fine - the LLM just sent bad input. Correcting
 * the input and retrying the same tool could succeed. The LLM may also choose to take
 * a different approach if correction seems unlikely to help.</p>
 *
 * <p><strong>Examples:</strong></p>
 * <ul>
 *   <li>Parameter out of valid range (e.g., maxResults=1000 when limit is 100)</li>
 *   <li>Invalid date format (e.g., "2024-13-01" when "YYYY-MM-DD" required)</li>
 *   <li>Missing required parameter</li>
 *   <li>Well-formed ID that doesn't exist (LLM can try a different ID)</li>
 *   <li>Guardrail violation (e.g., PII detected in input)</li>
 * </ul>
 *
 * <p><strong>Concrete subclasses:</strong></p>
 * <ul>
 *   <li>{@link InvalidInputException} - bad input from LLM (invalid params, HTTP 400/422)</li>
 *   <li>{@link ResourceNotFoundException} - well-formed ID that doesn't exist (HTTP 404 on fetch-by-ID)</li>
 *   <li>{@link GuardrailException} - guardrail violation (PII, prohibited content)</li>
 *   <li>{@link ai.redouble.nucleo.harness.errors.retry.JsonParseException} - the model's reply was not the JSON object asked for (the model can retry with the right shape)</li>
 *   <li>{@link ai.redouble.nucleo.harness.errors.retry.ResponseValidationException} - LLM output that parsed but left required fields empty</li>
 * </ul>
 *
 * @see UncorrectableLLMException
 * @see CorrectableRuntimeLLMException
 * @see LLMReadableCheckedException
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-21)
 */
public non-sealed abstract class CorrectableLLMException extends LLMReadableCheckedException {

    /**
     * Constructs a correctable exception with the specified detail message.
     *
     * @param message the detail message (for logging and debugging)
     */
    protected CorrectableLLMException(String message) {
        super(message);
    }

    /**
     * Constructs a correctable exception with the specified detail message and cause.
     *
     * @param message the detail message (for logging and debugging)
     * @param cause the cause (which is saved for later retrieval by {@link #getCause()})
     */
    protected CorrectableLLMException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public final boolean isCorrectable() {
        return true;
    }

    @Override
    public String explainToLLM() {
        return getLLMMessage() +
               "\n[This error may be correctable - you can retry with different parameters or try a different approach]";
    }
}
