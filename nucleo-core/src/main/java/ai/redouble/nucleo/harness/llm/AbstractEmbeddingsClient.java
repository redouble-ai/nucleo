/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm;

import ai.redouble.nucleo.*;
import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.http.*;
import ai.redouble.nucleo.harness.errors.retry.*;

import java.io.*;
import java.net.*;
import java.time.*;

/**
 * Abstract base class for embeddings client implementations.
 * Provides rate limiting infrastructure for embedding API calls.
 * <p>
 * Extends AbstractRateLimitedClient to inherit common rate limiting behavior.
 * Subclasses implement the actual API integration for specific providers.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-01-17)
 */
public abstract class AbstractEmbeddingsClient extends AbstractRateLimitedClient implements EmbeddingsClient {

    /**
     * What one provider call returned: the vector at the provider's native width and the
     * input tokens the provider says it billed, null where the provider reports none.
     */
    public record RawEmbedding(float[] vector, Integer inputTokens) {}

    /**
     * Embeds with rate limiting. Template method that handles rate limiting, records the
     * call on the response and delegates the wire call to the implementation.
     *
     * @param input the text to embed
     * @param purpose whether this is a stored corpus item or a live search probe
     * @throws IOException if API call fails
     * @throws InterruptedException if interrupted while waiting
     */
    @Override
    public final EmbeddingsResponse embed(String input, EmbeddingPurpose purpose) throws IOException, InterruptedException {
        EmbeddingsResponse response = new EmbeddingsResponse(model, purpose);
        RawEmbedding raw;
        try {
            raw = executeWithRateLimit(() -> {
                try {
                    return doCalculateEmbedding(input, purpose);
                } catch (IOException | InterruptedException e) {
                    if (e instanceof IOException && isQuotaError(e)) {
                        throw quotaExhausted(e);
                    }
                    if (e instanceof IOException && is429Error(e)) {
                        RateLimitInfo info = extractRateLimitInfo(e);
                        throw new RateLimitRetryException(
                            "Rate limit exceeded: " + e.getMessage(),
                            info,
                            0,
                            e.getMessage(),
                            info != null ? info.getRetryAfter() : null,
                            e
                        );
                    }
                    // Deployment configuration, not weather: the wrong region or endpoint builds
                    // names DNS has never heard of, and no retry changes DNS. A client that
                    // already refined the failure into a typed one keeps its own words.
                    UnknownHostException unknownHost = e instanceof LLMReadable ? null : AbstractLLMClient.findUnknownHost(e);
                    if (unknownHost != null) {
                        throw new UncorrectableRuntimeLLMException("The endpoint does not exist: " + unknownHost.getMessage()
                                + ". " + (model != null ? model.getId() : "This model")
                                + " is not served where this deployment points. Not retried: no retry changes DNS.", e);
                    }
                    if (e instanceof IOException && isServerError(e)) {
                        // 5xx is transient and the provider marks it retryable; signal the dispatcher's
                        // backoff retry instead of failing the embedding, the same discipline as the
                        // LLM clients.
                        String provider = model != null ? model.getId() : "unknown";
                        String details = UpstreamRetryException.details(e);
                        throw new TransientErrorRetryException("Server error: " + details, provider, details, 0, 0, e);
                    }
                    throw new RuntimeException(e);
                }
            });
        } catch (UpstreamRetryException | QuotaExhaustedException e) {
            // Framework control signals the dispatcher acts on by type, so they
            // must propagate as themselves rather than be unwrapped to the
            // IOException cause below: an UpstreamRetryException is a transparent
            // retry signal (re-run with backoff); QuotaExhaustedException is its
            // opposite, an out-of-money failure that must surface clearly and
            // never be retried.
            throw e;
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException) {
                throw (IOException) e.getCause();
            }
            if (e.getCause() instanceof InterruptedException) {
                throw (InterruptedException) e.getCause();
            }
            throw e;
        }
        // A spec that pins its native output dimension is coerced to that width, so a
        // native-width vector is normalized in place rather than round-tripped through
        // a lossy expand/fold against the framework canonical dimension.
        Integer specDimensions = model != null ? model.getEmbeddingDimensions() : null;
        response.setVector(DimensionAdapter.coerce(raw.vector(), specDimensions != null ? specDimensions : EmbeddingsClient.DIMENSIONS));
        response.setActualInputTokens(raw.inputTokens());
        response.setEndTime(Instant.now());
        response.setSuccessful(true);
        return response;
    }

    /**
     * Implementation-specific method to calculate embeddings.
     * Subclasses implement this to make their actual API calls. The returned
     * vector is the provider's native output; the template method coerces it
     * to the canonical dimension. The token count is what the provider billed
     * for the input, which is what the call costs.
     *
     * @param input the text to embed
     * @param purpose whether this is a stored corpus item or a live search probe
     * @return the raw provider embedding and its billed input tokens
     * @throws IOException if API call fails
     * @throws InterruptedException if interrupted
     */
    protected abstract RawEmbedding doCalculateEmbedding(String input, EmbeddingPurpose purpose) throws IOException, InterruptedException;

    /**
     * Checks if the given exception represents a rate limit error (HTTP 429).
     * Subclasses must implement based on their HTTP client/SDK.
     *
     * @param e the exception to check
     * @return true if this is a rate limit error
     */
    protected abstract boolean is429Error(Exception e);

    /**
     * True if the failure (or any cause) is an HTTP 5xx from the embeddings API - a transient
     * server error that should be retried with backoff (providers explicitly mark these
     * retryable) - or a failure to reach the API at all. Clients on the framework's own HTTP
     * transport ({@code AbstractApiClient}) fail with a typed {@link HttpErrorResponse} for any
     * status the service answered and with an {@link ExternalServiceException} carrying no
     * status when the service was not reached, so the verdict is read off the types. A provider
     * on another transport (an SDK) overrides.
     */
    protected boolean isServerError(Exception e) {
        boolean answered = false;
        boolean serviceFailure = false;
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof HttpErrorResponse http) {
                answered = true;
                if (http.getStatusCode() >= 500) {
                    return true;
                }
            }
            if (c instanceof ExternalServiceException) {
                serviceFailure = true;
            }
        }
        return serviceFailure && !answered;
    }

    /**
     * Extracts rate limit information from an exception if possible.
     * May return null if the information is not available.
     *
     * @param e the exception containing rate limit info
     * @return rate limit info or null
     */
    protected abstract RateLimitInfo extractRateLimitInfo(Exception e);
}
