/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors;

/**
 * Runtime (unchecked) exception indicating the LLM's input was wrong and it may fix
 * its parameters and retry.
 *
 * <p>This is the unchecked counterpart of {@link CorrectableLLMException}. Use when
 * checked exceptions are impractical but the error is still correctable by the LLM.</p>
 *
 * <p><strong>Example usage:</strong></p>
 * <pre>{@code
 * // In a stream operation where checked exceptions can't be thrown
 * items.stream().map(item -> {
 *     try {
 *         return Integer.parseInt(item);
 *     } catch (NumberFormatException e) {
 *         throw new CorrectableRuntimeLLMException(
 *             "Parameter 'items' contains non-integer value: " + item, e);
 *     }
 * });
 * }</pre>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-03-13)
 * @see CorrectableLLMException
 * @see UncorrectableRuntimeLLMException
 */
public non-sealed class CorrectableRuntimeLLMException extends LLMReadableRuntimeException {

    /**
     * Constructs a correctable runtime exception with the specified LLM message.
     *
     * @param llmMessage clean, actionable error message for LLM understanding
     */
    public CorrectableRuntimeLLMException(String llmMessage) {
        super(llmMessage);
    }

    /**
     * Constructs a correctable runtime exception with the specified LLM message and cause.
     *
     * @param llmMessage clean, actionable error message for LLM understanding
     * @param cause the cause (preserved for developer debugging)
     */
    public CorrectableRuntimeLLMException(String llmMessage, Throwable cause) {
        super(llmMessage, cause);
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
