/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors;

/**
 * Sealed interface for exceptions that can be communicated to LLMs in an actionable form.
 * Extends {@link LLMReadable} to inherit the {@code getLLMMessage()} contract,
 * adding exception-specific concerns: correctability and error explanation formatting.
 *
 * <p>The sealed hierarchy ensures exactly two entry points:</p>
 * <ul>
 *   <li>{@link LLMReadableCheckedException} - checked exceptions (used by tools, thinkers, doers)</li>
 *   <li>{@link LLMReadableRuntimeException} - unchecked exceptions (for cases where checked
 *       exceptions are impractical, e.g. deep callback chains, stream operations)</li>
 * </ul>
 *
 * <p>Both branches further split into correctable (LLM can fix its input and retry) and
 * uncorrectable (LLM should try a different approach):</p>
 * <pre>{@code
 * LLMReadable (marker interface)
 *   +-- LLMReadableException (sealed, extends LLMReadable)
 *         +-- LLMReadableCheckedException (sealed, checked)
 *         |     +-- CorrectableLLMException (non-sealed abstract)
 *         |     |     +-- InvalidInputException, ResourceNotFoundException, GuardrailException, ...
 *         |     +-- UncorrectableLLMException (non-sealed abstract)
 *         |           +-- ExternalServiceException, UnauthorizedException, SystemException, ...
 *         +-- LLMReadableRuntimeException (sealed, unchecked)
 *               +-- CorrectableRuntimeLLMException (non-sealed concrete)
 *               +-- UncorrectableRuntimeLLMException (non-sealed concrete)
 * }</pre>
 *
 * <p>The thinker loop checks {@code instanceof LLMReadableException} to recognize both checked and
 * unchecked variants, then uses {@link #isCorrectable()} to decide retry behavior.</p>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-03-13)
 * @see LLMReadable
 * @see LLMReadableCheckedException
 * @see LLMReadableRuntimeException
 */
public sealed interface LLMReadableException extends LLMReadable permits LLMReadableCheckedException, LLMReadableRuntimeException {

    /**
     * Explains the error to the LLM for inclusion in the conversation context.
     *
     * <p>The default implementation returns just the LLM message. Subclasses typically
     * append a hint indicating whether the error is correctable or not, so the LLM
     * knows whether to retry or try a different approach.</p>
     *
     * @return full error explanation for the LLM
     */
    default String explainToLLM() {
        return getLLMMessage();
    }

    /**
     * Indicates whether the LLM may retry the same tool with different input.
     *
     * <p><strong>true (correctable):</strong> The LLM's input was wrong. It may fix its
     * parameters and call the same tool again.</p>
     *
     * <p><strong>false (uncorrectable):</strong> The tool itself cannot work right now,
     * regardless of input. The LLM should try a different approach.</p>
     *
     * @return true if correctable, false if uncorrectable
     */
    boolean isCorrectable();
}
