/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import ai.redouble.nucleo.harness.decision.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;

import java.io.*;
import java.time.*;

/**
 * Wrapper for decision clients that records every call on the {@link JobContext}, the way
 * {@link ObservableLLMClient} records chat calls and {@link ObservableEmbeddingsClient}
 * embeddings: a success with its answers and what the endpoint billed, a failure with why.
 * The terminal event carries both to the cost ledger, the meters, the spans and the usage
 * tables, so decision spend is counted and capped like any other model call, and the
 * distributions a run produced stay on its record.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
class ObservableDecisionClient implements DecisionClient {
    private final DecisionClient delegate;
    private final JobContext<?> jobContext;

    ObservableDecisionClient(DecisionClient delegate, JobContext<?> jobContext) {
        this.delegate = delegate;
        this.jobContext = jobContext;
    }

    @Override
    public DecisionResponse decide(DecisionRequest request) throws IOException, InterruptedException {
        Instant startTime = Instant.now();
        try {
            DecisionResponse response = delegate.decide(request);
            jobContext.addLlmResponse(response);
            return response;
        }
        catch (Exception e) {
            DecisionResponse failed = new DecisionResponse(delegate.getModel(), request);
            failed.setStartTime(startTime);
            failed.setEndTime(Instant.now());
            failed.setSuccessful(false);
            failed.setReasonForFailure(e.getMessage());
            failed.setLastError(e);
            jobContext.addLlmResponse(failed);
            throw e;
        }
    }

    @Override
    public ModelSpec getModel() {
        return delegate.getModel();
    }

    @Override
    public void setModel(ModelSpec model) {
        delegate.setModel(model);
    }
}
