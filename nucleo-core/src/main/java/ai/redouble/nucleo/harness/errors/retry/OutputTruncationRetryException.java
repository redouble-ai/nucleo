/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors.retry;


/**
 * Signals that the provider returned {@code stop_reason=max_tokens} and the job
 * should be re-run with a larger output budget. The framework catches this in
 * {@code JobDispatcher.executeWithRetry} and restarts the job with a fresh
 * reservation; the caller (normally {@link ai.redouble.nucleo.tools.thinking.LLMCall}) is
 * expected to have mutated the outgoing message's
 * {@code requestedOutputTokens} before rethrowing, so the next iteration's
 * {@link ai.redouble.nucleo.harness.JobRequirements} and the SDK's {@code max_tokens}
 * field both resolve to the new budget.
 *
 * <p>This is NOT a {@link RateLimitRetryException} or an
 * {@link ai.redouble.nucleo.harness.errors.LLMReadableException}: it is a deterministic
 * framework retry, invisible to the LLM and to the end user.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-15)
 */
public class OutputTruncationRetryException extends RuntimeException {
    private final String modelName;
    private final int previousBudget;
    private final int newBudget;

    public OutputTruncationRetryException(String modelName, int previousBudget, int newBudget) {
        super("Output truncated on " + modelName + " at " + previousBudget
                + " tokens; retrying with budget " + newBudget);
        this.modelName = modelName;
        this.previousBudget = previousBudget;
        this.newBudget = newBudget;
    }

    public String getModelName() {
        return modelName;
    }

    public int getPreviousBudget() {
        return previousBudget;
    }

    public int getNewBudget() {
        return newBudget;
    }
}
