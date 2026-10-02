/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.retry.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;

import java.time.*;
import java.util.function.*;

/**
 * Wrapper for LLM clients that captures all responses for observability.
 * Every {@link LLMResponse} (success, failure, streaming) is recorded on the
 * {@link JobContext}, for a deployment's usage recorder to persist.
 * Every call goes through a request, so every call is captured.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-14)
 */
class ObservableLLMClient implements LLMClient {
    private final LLMClient delegate;
    private final JobContext<?> jobContext;

    public ObservableLLMClient(LLMClient delegate, JobContext<?> jobContext) {
        this.delegate = delegate;
        this.jobContext = jobContext;
    }

    @Override
    public <T> LLMResponse<T> singleResponse(LLMRequest<T> request) throws TokenEstimateExceedsLimitException {
        Instant startTime = Instant.now();
        try {
            LLMResponse<T> response = delegate.singleResponse(request);
            jobContext.addLlmResponse(response);
            return response;
        }
        catch (TokenEstimateExceedsLimitException | RuntimeException e) {
            recordFailure(request, startTime, e);
            throw e;
        }
    }

    @Override
    public <T> LLMResponse<T> streamResponse(LLMRequest<T> request, Consumer<StreamChunk> chunkHandler) throws TokenEstimateExceedsLimitException {
        Instant startTime = Instant.now();
        try {
            LLMResponse<T> response = delegate.streamResponse(request, chunkHandler);
            if (response.getStartTime() == null) {
                response.setStartTime(startTime);
            }
            jobContext.addLlmResponse(response);
            return response;
        }
        catch (TokenEstimateExceedsLimitException | RuntimeException e) {
            recordFailure(request, startTime, e);
            throw e;
        }
    }

    /**
     * Records a call that produced no response, so the input log is persisted with the
     * failure. A truncation retry IS the max_tokens stop reason: the failed response is
     * labelled so the recorded call is queryable by it instead of landing as UNKNOWN, the default on
     * this fresh response, which is not the truncated one the delegate produced.
     */
    private <T> void recordFailure(LLMRequest<T> request, Instant startTime, Exception e) {
        LLMResponse<T> failed = new LLMResponse<>(request);
        failed.setStartTime(startTime);
        failed.setEndTime(Instant.now());
        failed.setSuccessful(false);
        failed.setReasonForFailure(e.getMessage());
        failed.setLastError(e);
        failed.setModel(delegate.getModelIdentifier());
        if (e instanceof OutputTruncationRetryException) {
            failed.setStopReason(LLMStopReason.MAX_TOKENS);
        }
        jobContext.addLlmResponse(failed);
    }

    @Override
    public Double getTemperature() {
        return delegate.getTemperature();
    }

    @Override
    public void setTemperature(Double temperature) {
        delegate.setTemperature(temperature);
    }

    @Override
    public String getModelIdentifier() {
        return delegate.getModelIdentifier();
    }

    @Override
    public <T> OutgoingMessage<T> createOutgoingMessage(final ResponseHandler<T> responseHandler) {
        return delegate.createOutgoingMessage(responseHandler);
    }

    @Override
    public void setModel(final ModelSpec model) {
        delegate.setModel(model);
    }

    @Override
    public ModelSpec getModel() {
        return delegate.getModel();
    }

    @Override
    public ContentFormatter getFormatter() {
        return delegate.getFormatter();
    }

    @Override
    public APIDialect getDialect() {
        return delegate.getDialect();
    }
}