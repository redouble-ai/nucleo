/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors;

import java.time.*;

/**
 * Thrown when the dispatcher terminates a job because it exceeded its execution
 * time budget. The job's resources were reclaimed out from under whatever it was
 * doing, so the in-flight operation (a DB query, an HTTP call) fails with a raw
 * downstream exception. This type wraps that casualty and carries the original
 * as its cause, so the LLM and any synchronous caller see an honest framework
 * exception instead of a cryptic "Statement closed" or "Connection is closed".
 *
 * <p>Uncorrectable: the job is gone. Retrying the same tool will not bring it
 * back; the agent should take a different approach or report the limitation.</p>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-14)
 */
public class JobTimeoutException extends UncorrectableLLMException {
    private final Duration budget;

    public JobTimeoutException(Duration budget, Throwable cause) {
        super("Job exceeded its time budget" + (budget != null ? " of " + budget : "")
                + " and was stopped; its resources were reclaimed", cause);
        this.budget = budget;
    }

    @Override
    public String getLLMMessage() {
        return "The operation was stopped because the job exceeded its allotted time budget"
                + (budget != null ? " (" + budget + ")" : "")
                + " and its resources were reclaimed. The result is unavailable.";
    }

    public Duration getBudget() {
        return budget;
    }
}
