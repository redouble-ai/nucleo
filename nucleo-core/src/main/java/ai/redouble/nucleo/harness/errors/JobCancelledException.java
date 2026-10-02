/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors;

import ai.redouble.nucleo.harness.*;

/**
 * Thrown when the dispatcher terminates a job because it was cancelled before
 * completion. Cancellation only sets a flag; the running job discovers it by
 * calling {@link JobContext#checkCancellation()}, which throws the nested
 * {@link JobContext.CancellationException}. That raw signal (or the
 * {@link java.util.concurrent.CancellationException} from a pre-execution cancel)
 * is wrapped here so callers and the LLM see an honest framework exception, with
 * the original preserved as the cause.
 *
 * <p>Uncorrectable: the job will not resume. The agent should take a different
 * approach or report that the work was cancelled.</p>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-14)
 */
public class JobCancelledException extends UncorrectableLLMException {
    public JobCancelledException(Throwable cause) {
        super("Job was cancelled before completion", cause);
    }

    @Override
    public String getLLMMessage() {
        return "The operation was cancelled before it completed. The result is unavailable.";
    }
}
