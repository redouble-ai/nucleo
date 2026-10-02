/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors;


/**
 * Base class for exceptions where the tool itself cannot work right now, regardless of
 * what the LLM sends. The LLM should <em>not retry this tool</em> - instead it should
 * try a different tool, take an alternative approach, or explain the limitation to the user.
 *
 * <p>The key semantic: no amount of parameter tweaking will help. The problem is external
 * to the LLM's input - a service is down, credentials are invalid, the system has a bug.
 * Retrying the same tool with different parameters is pointless.</p>
 *
 * <p><strong>Examples:</strong></p>
 * <ul>
 *   <li>External API unavailable (LLM should try alternative data sources)</li>
 *   <li>Network timeout (LLM cannot fix network issues)</li>
 *   <li>API key missing/invalid (LLM cannot provide credentials)</li>
 *   <li>Permission denied (LLM cannot fix permissions)</li>
 *   <li>System errors (bugs, infrastructure failures)</li>
 * </ul>
 *
 * <p><strong>Concrete subclasses:</strong></p>
 * <ul>
 *   <li>{@link ExternalServiceException} - API down, HTTP 429/500+, network failures</li>
 *   <li>{@link UnauthorizedException} - external API rejected credentials (HTTP 401/403)</li>
 *   <li>{@link PermissionDeniedException} - internal permission check failed</li>
 *   <li>{@link SystemException} - bugs, infrastructure failures</li>
 *   <li>{@link JobCancelledException}, {@link JobTimeoutException} - the dispatcher stopped the job</li>
 *   <li>{@link ai.redouble.nucleo.harness.JobContext.CancellationException} - the signal a running job gets from {@code checkCancellation()}</li>
 *   <li>{@link ai.redouble.nucleo.harness.errors.retry.TokenEstimateExceedsLimitException} - the conversation cannot fit the model's window</li>
 *   <li>{@link ai.redouble.nucleo.harness.conversation.ContextOverflowException} - compaction could not make it fit</li>
 *   <li>{@link ai.redouble.nucleo.prompt.PromptNotFoundException} - no prompt source holds the key</li>
 * </ul>
 *
 * @see CorrectableLLMException
 * @see UncorrectableRuntimeLLMException
 * @see LLMReadableCheckedException
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-21)
 */
public non-sealed abstract class UncorrectableLLMException extends LLMReadableCheckedException {

    /**
     * Constructs an uncorrectable exception with the specified detail message.
     *
     * @param message the detail message (for logging and debugging)
     */
    protected UncorrectableLLMException(String message) {
        super(message);
    }

    /**
     * Constructs an uncorrectable exception with the specified detail message and cause.
     *
     * @param message the detail message (for logging and debugging)
     * @param cause the cause (which is saved for later retrieval by {@link #getCause()})
     */
    protected UncorrectableLLMException(String message, Throwable cause) {
        super(message, cause);
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
