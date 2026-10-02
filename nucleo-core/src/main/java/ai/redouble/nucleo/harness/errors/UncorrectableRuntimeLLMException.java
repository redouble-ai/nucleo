/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors;


/**
 * Runtime (unchecked) exception indicating the tool cannot work right now, regardless
 * of what the LLM sends. The LLM should try a different approach.
 *
 * <p>This is the unchecked counterpart of {@link UncorrectableLLMException}. Use when
 * checked exceptions are impractical but the error is uncorrectable by the LLM.</p>
 *
 * <p><strong>Example usage:</strong></p>
 * <pre>{@code
 * // In a callback where checked exceptions can't propagate
 * connectionPool.withConnection(conn -> {
 *     if (conn.isClosed()) {
 *         throw new UncorrectableRuntimeLLMException(
 *             "Database connection unavailable - try a different approach");
 *     }
 *     return query(conn);
 * });
 * }</pre>
 *
 * <p><strong>Subclasses:</strong></p>
 * <ul>
 *   <li>{@link ai.redouble.nucleo.harness.errors.retry.QuotaExhaustedException} - the provider account is out of money</li>
 *   <li>{@link SpendCapExceededException} - admission refused the job under its workflow's spend cap</li>
 *   <li>{@link ai.redouble.nucleo.harness.models.ModelNotFoundException} - the catalog holds no model under the id</li>
 *   <li>{@link ProviderRefusalException} - the provider declined the call; typed so a caller can resubmit on another model</li>
 * </ul>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-03-13)
 * @see UncorrectableLLMException
 * @see CorrectableRuntimeLLMException
 */
public non-sealed class UncorrectableRuntimeLLMException extends LLMReadableRuntimeException {

    /**
     * Constructs an uncorrectable runtime exception with the specified LLM message.
     *
     * @param llmMessage clean, actionable error message for LLM understanding
     */
    public UncorrectableRuntimeLLMException(String llmMessage) {
        super(llmMessage);
    }

    /**
     * Constructs an uncorrectable runtime exception with the specified LLM message and cause.
     *
     * @param llmMessage clean, actionable error message for LLM understanding
     * @param cause the cause (preserved for developer debugging)
     */
    public UncorrectableRuntimeLLMException(String llmMessage, Throwable cause) {
        super(llmMessage, cause);
    }

    @Override
    public final boolean isCorrectable() {
        return false;
    }

    @Override
    public String explainToLLM() {
        return getLLMMessage() +
               "\n[This error is not correctable - consider an alternative approach]";
    }
}
