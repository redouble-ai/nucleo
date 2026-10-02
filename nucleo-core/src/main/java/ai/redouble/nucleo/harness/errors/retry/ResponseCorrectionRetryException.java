/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors.retry;


/**
 * Signals that an LLM response failed to parse or validate, a correction message
 * has already been appended to the conversation, and the job should be re-run so
 * the model can fix its output. The framework catches this in
 * {@code JobDispatcher.executeWithRetry} and restarts the job transparently; the
 * caller (normally {@link ai.redouble.nucleo.tools.thinking.LLMCall}) tracks its own correction
 * budget and stops throwing once that budget is exhausted, so the dispatcher's
 * cap is only a backstop against runaway jobs.
 *
 * <p>This is NOT an {@link ai.redouble.nucleo.harness.errors.LLMReadableException}: like
 * {@link OutputTruncationRetryException}, it is a deterministic framework retry,
 * invisible to the LLM above and to the end user. The underlying correctable
 * failure travels as the cause.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-09)
 */
public class ResponseCorrectionRetryException extends RuntimeException {
    private final String modelName;
    private final int correctionAttempt;

    public ResponseCorrectionRetryException(String modelName, int correctionAttempt, Throwable cause) {
        super("LLM response on " + modelName + " needs correction (attempt " + correctionAttempt
                + "): " + cause.getMessage(), cause);
        this.modelName = modelName;
        this.correctionAttempt = correctionAttempt;
    }

    public String getModelName() {
        return modelName;
    }

    public int getCorrectionAttempt() {
        return correctionAttempt;
    }
}
