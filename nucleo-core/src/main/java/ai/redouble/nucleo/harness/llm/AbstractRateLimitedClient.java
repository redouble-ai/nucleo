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

    /**
     * No id here: a client that reads none from its provider's error responses has none to
     * give. A provider client overrides this with its provider's header.
     */
    @Override
    public String providerRequestId(Exception failure) {
        return null;
    }

    /**
     * A failure's text, stating the provider's request id. Every exception a client builds
     * from a provider's failure takes its message through here, so the id reads the same
     * wherever it appears and lands in the log line and the recorded error, which hold the
     * message and nothing else of the exception.
     *
     * <p>The text comes back unchanged when the failure carries no id, and when it already
     * states the id: the AWS SDK prints the request id in its own exception message, and a
     * text that says it once is not made to say it twice.</p>
     *
     * @param text the failure's text, null when the provider gave no words
     * @param failure what the provider's client threw
     * @return the text, ending in {@code (request id <id>)} when the id is known and absent from it
     */
    protected String statingRequestId(String text, Exception failure) {
        return statingRequestId(text, providerRequestId(failure));
    }

    /**
     * The same rendering for a caller that already holds the id, as a client does when the
     * provider failed a request inside a response that arrived whole.
     *
     * @param text the failure's text, null when the provider gave no words
     * @param requestId the provider's request id, null when there is none
     * @return the text, ending in {@code (request id <id>)} when the id is known and absent from it
     */
    protected static String statingRequestId(String text, String requestId) {
        if (requestId == null) {
            return text;
        }
        if (text == null) {
            return "request id " + requestId;
        }
        if (text.contains(requestId)) {
            return text;
        }
        return text + " (request id " + requestId + ")";
    }

    /** Builds the out-of-money exception from the provider's failure. */
    protected QuotaExhaustedException quotaExhausted(Exception cause) {
        String provider = model != null ? model.getProviderKey() : "unknown";
        String modelName = model != null ? model.getId() : null;
        return new QuotaExhaustedException(provider, accountIdentifier(), modelName,
                cause != null ? statingRequestId(cause.getMessage(), cause) : null, cause);
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
