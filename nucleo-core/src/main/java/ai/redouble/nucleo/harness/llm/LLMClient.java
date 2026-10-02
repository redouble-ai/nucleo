/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm;

import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.retry.*;

import java.util.function.*;

/**
 * Interface for LLM client implementations across different providers.
 * Provides unified API for text generation with structured output support.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-07-18)
 */
public interface LLMClient extends Client {
    /**
     * Gets a structured response from the LLM with retry logic and validation.
     * Returns an LLMResponse with successful=false if the request fails.
     *
     * @param request The LLM request with prompt, retry settings, and optional JSON validation
     * @return The LLM response with metadata, tokens used, and parsed response
     * @throws TokenEstimateExceedsLimitException if pre-flight estimate exceeds model limits
     */
    <T> LLMResponse<T> singleResponse(LLMRequest<T> request) throws TokenEstimateExceedsLimitException;

    /** The caller-set sampling temperature, or null when none was set and the provider's own default applies. */
    Double getTemperature();

    /**
     * Sets the sampling temperature this client's requests carry. Unset (null, the default),
     * no temperature reaches the wire and the provider's own default applies - which is also
     * what keeps the models that accept no sampling parameter callable. A temperature set on
     * a model that rejects it comes back as the provider's own typed refusal.
     */
    void setTemperature(Double temperature);

    String getModelIdentifier();

    /**
     * Creates a new conversation message appropriate for this client.
     * Different providers may return different message implementations
     * (e.g., AnthropicMessage, OpenAIMessage) with provider-specific validation.
     *
     * @return a new empty conversation message
     */
    <T> OutgoingMessage<T> createOutgoingMessage(ResponseHandler<T> responseHandler);

    /**
     * Streams response chunks from the LLM and returns the full response
     * record. The {@code chunkHandler} receives each delta as it arrives;
     * the returned {@link LLMResponse} carries token usage, cache counters,
     * stop reason, provider, and other fields populated by the client.
     *
     * @return the populated response (check {@link LLMResponse#isSuccessful()})
     * @throws TokenEstimateExceedsLimitException if pre-flight estimate exceeds model limits
     */
    default <T> LLMResponse<T> streamResponse(LLMRequest<T> request, Consumer<StreamChunk> chunkHandler)
            throws TokenEstimateExceedsLimitException {
        throw new UnsupportedOperationException("Streaming not supported for " + getClass().getSimpleName());
    }

    /**
     * Releases resources held by this client (e.g., HTTP connection pools).
     * Called during application shutdown for shared clients.
     */
    default void close() {}

    /**
     * Gets the content formatter for this client.
     * Formatters handle provider-specific content encoding.
     *
     * @return the content formatter
     */
    ContentFormatter getFormatter();

    /**
     * Gets the API dialect for this client.
     * Used to determine encoding strategies and compatibility.
     *
     * @return the API dialect
     */
    APIDialect getDialect();
}
