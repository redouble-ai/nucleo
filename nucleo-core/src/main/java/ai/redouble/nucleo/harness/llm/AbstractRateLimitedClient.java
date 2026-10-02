/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm;

import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.errors.retry.*;
import ai.redouble.nucleo.harness.models.*;

/**
 * Base class for API clients with rate limiting support. Wraps the upstream
 * API call so success/failure feedback flows into the model's
 * {@link TokenBucketRateLimiter}: successes decay the throttle coefficient,
 * 429s stretch it. An entry bounded by a concurrency ({@code max_concurrent})
 * has a gate instead of a bucket and nothing adaptive to feed, so the feedback
 * is skipped for it.
 *
 * <p>The local token bucket is debited exactly once per call, by the parent
 * job's {@code JobResources} via the resolved bindings of {@link ai.redouble.nucleo.harness.JobRequirements#requireModel}.
 * This class does not acquire a second time - it only observes the outcome.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-10-19)
 */
public abstract class AbstractRateLimitedClient implements Client {
    protected ModelSpec model;

    @Override
    public ModelSpec getModel() {
        return model;
    }

    @Override
    public void setModel(ModelSpec model) {
        if (model == null) {
            throw new IllegalArgumentException("Model cannot be null");
        }
        this.model = model;
    }

    /**
     * Whether the exception signals the provider account is out of money
     * (credits/quota exhausted, budget at zero) rather than a transient rate
     * limit. Default false; providers that have a distinct billing-exhaustion
     * signal override this (OpenAI, Anthropic). Providers whose only "quota"
     * errors are capacity/throttle limits (AWS Bedrock service quotas, Azure
     * deployment rate limits) deliberately do not - those are retryable and
     * stay on the rate-limit path.
     */
    protected boolean isQuotaError(Exception e) {
        return false;
    }

    /**
     * Human-facing identifier of the billing account behind this client, for
     * the {@link QuotaExhaustedException} message. Defaults to the provider
     * key; providers override to name the specific credential.
     */
    protected String accountIdentifier() {
        return model != null ? model.getProviderKey() : "unknown";
    }

    /**
     * True if the throwable's message, or any message in its cause chain,
     * contains any of the needles (case-insensitive). Shared so provider
     * classifiers express their billing/rate signals without re-walking the
     * chain by hand.
     */
    protected static boolean messageChainContains(Throwable e, String... needles) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg == null) {
                continue;
            }
            String lower = msg.toLowerCase();
            for (String needle : needles) {
                if (lower.contains(needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Builds the out-of-money exception from the provider's failure. */
    protected QuotaExhaustedException quotaExhausted(Exception cause) {
        String provider = model != null ? model.getProviderKey() : "unknown";
        String modelName = model != null ? model.getId() : null;
        return new QuotaExhaustedException(provider, accountIdentifier(), modelName,
                cause != null ? cause.getMessage() : null, cause);
    }

    protected final <T> T executeWithRateLimit(RateLimitedOperation<T> operation) {
        // an entry bounded by a concurrency has a gate, not a bucket: nothing adaptive to feed
        TokenBucketRateLimiter rateLimiter = model != null && model.getMaxConcurrent() == null
                ? RateLimiterRegistry.getInstance().getRateLimiter(model)
                : null;
        try {
            T result = operation.execute();
            if (rateLimiter != null) {
                rateLimiter.recordSuccess();
            }
            return result;
        }
        catch (UpstreamRetryException e) {
            // Every upstream slow-down signal feeds the one adaptive throttle, weighted
            // by the signal itself: a 429 one increment, a 529 several, a plain 5xx
            // none - the coefficient converges on whatever rate the provider is
            // actually willing to serve.
            int increments = e.backpressureIncrements();
            if (rateLimiter != null && increments > 0) {
                rateLimiter.recordBackpressure(increments);
            }
            throw e;
        }
    }

    @FunctionalInterface
    protected interface RateLimitedOperation<T> {
        T execute();
    }
}
