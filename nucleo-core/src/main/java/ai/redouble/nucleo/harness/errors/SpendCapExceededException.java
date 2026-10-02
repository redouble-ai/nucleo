/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors;

/**
 * A job was refused at admission because the spend its reservation would commit, on top
 * of what its workflow has already spent, crosses the cap the workflow runs under; or
 * because the cap is in one currency and the job's model is priced in another, or not
 * priced at all, so nothing can be said about the spend. Uncorrectable: no change of input
 * makes the money appear. An orchestrator that sees this stops submitting.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
public class SpendCapExceededException extends UncorrectableRuntimeLLMException {
    public SpendCapExceededException(String llmMessage) {
        super(llmMessage);
    }
}
