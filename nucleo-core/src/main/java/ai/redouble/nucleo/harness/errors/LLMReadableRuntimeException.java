/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors;

/**
 * Base class for unchecked exceptions that can be communicated to LLMs in an actionable form.
 *
 * <p>This is the runtime (unchecked) counterpart of {@link LLMReadableCheckedException}. Use this
 * when checked exceptions are impractical - deep callback chains, stream operations, or
 * code that crosses API boundaries not declaring {@code throws LLMReadableCheckedException}.</p>
 *
 * <p>Two concrete subclasses provide the correctable/uncorrectable distinction:</p>
 * <ul>
 *   <li>{@link CorrectableRuntimeLLMException} - the LLM's input was wrong, retry may help</li>
 *   <li>{@link UncorrectableRuntimeLLMException} - the tool cannot work now, try something else</li>
 * </ul>
 *
 * <p>The thinker loop recognizes these via {@code instanceof LLMReadableException}, same as checked
 * variants. The correctable/uncorrectable classification is preserved without wrapping.</p>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-03-13)
 * @see LLMReadableException
 * @see LLMReadableCheckedException
 */
public sealed abstract class LLMReadableRuntimeException extends RuntimeException implements LLMReadableException
        permits CorrectableRuntimeLLMException, UncorrectableRuntimeLLMException {
    private final String llmMessage;

    /**
     * Constructs a runtime LLM-readable exception with the specified LLM message.
     *
     * @param llmMessage clean, actionable error message for LLM understanding
     */
    protected LLMReadableRuntimeException(String llmMessage) {
        super(llmMessage);
        this.llmMessage = llmMessage;
    }

    /**
     * Constructs a runtime LLM-readable exception with the specified LLM message and cause.
     *
     * @param llmMessage clean, actionable error message for LLM understanding
     * @param cause the cause (preserved for developer debugging)
     */
    protected LLMReadableRuntimeException(String llmMessage, Throwable cause) {
        super(llmMessage, cause);
        this.llmMessage = llmMessage;
    }

    @Override
    public String getLLMMessage() {
        return llmMessage;
    }

    /**
     * Unwraps a throwable to find a buried {@link LLMReadableException} exception and rethrow it
     * as an unchecked exception. Use this in code that cannot declare checked exceptions
     * (interface implementations, callbacks, lambdas) but still needs to preserve
     * the correctable/uncorrectable classification from the cause chain.
     *
     * <p>Walks the cause chain looking for any {@link LLMReadableException}. If found, wraps it
     * in the appropriate runtime variant preserving the LLM message and classification.
     * If none found, wraps in {@link UncorrectableRuntimeLLMException} with the
     * fallback message.</p>
     *
     * <p><strong>Example:</strong></p>
     * <pre>{@code
     * // In a PersistentStore method that can't declare checked exceptions
     * try {
     *     return dispatcher.submit(job).get();
     * } catch (Exception e) {
     *     throw LLMReadableRuntimeException.unwrapRuntime(e,
     *         "Failed to load conversation " + conversationId);
     * }
     * }</pre>
     *
     * @param t the throwable to unwrap
     * @param fallbackMessage message to use if no LLM-readable exception is found in the chain
     * @return a runtime LLM-readable exception preserving classification from the cause chain
     */
    public static LLMReadableRuntimeException unwrapRuntime(Throwable t, String fallbackMessage) {
        Throwable current = t;
        while (current != null) {
            if (current instanceof LLMReadableRuntimeException runtime) {
                return runtime;
            }
            if (current instanceof LLMReadableException lr) {
                if (lr.isCorrectable()) {
                    return new CorrectableRuntimeLLMException(lr.getLLMMessage(), t);
                }
                return new UncorrectableRuntimeLLMException(lr.getLLMMessage(), t);
            }
            current = current.getCause();
        }
        return new UncorrectableRuntimeLLMException(fallbackMessage, t);
    }
}
