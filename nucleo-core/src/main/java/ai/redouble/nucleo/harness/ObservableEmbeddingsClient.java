/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;

import java.io.*;
import java.time.*;

/**
 * Wrapper for embeddings clients that records every call on the {@link JobContext}, the
 * way {@link ObservableLLMClient} records chat calls: a success with what the provider
 * billed, a failure with why. The terminal event carries both to the cost ledger, the
 * meters, the spans and the usage tables, so embeddings spend is counted and capped like
 * any other model call.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-15)
 */
class ObservableEmbeddingsClient implements EmbeddingsClient {
    private final EmbeddingsClient delegate;
    private final JobContext<?> jobContext;

    ObservableEmbeddingsClient(EmbeddingsClient delegate, JobContext<?> jobContext) {
        this.delegate = delegate;
        this.jobContext = jobContext;
    }

    @Override
    public EmbeddingsResponse embed(String input, EmbeddingPurpose purpose) throws IOException, InterruptedException {
        Instant startTime = Instant.now();
        try {
            EmbeddingsResponse response = delegate.embed(input, purpose);
            jobContext.addLlmResponse(response);
            return response;
        }
        catch (Exception e) {
            EmbeddingsResponse failed = new EmbeddingsResponse(delegate.getModel(), purpose);
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
