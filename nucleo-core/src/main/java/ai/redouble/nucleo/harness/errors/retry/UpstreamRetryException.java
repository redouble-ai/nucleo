/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors.retry;

import ai.redouble.nucleo.harness.admission.*;

/**
 * A provider could not serve this attempt and the framework should retry it
 * transparently - without charging the job's own retry budget. The dispatcher has one
 * backoff loop for every such signal; what varies by signal is carried here as data:
 * how long to wait before the next attempt, and how hard the model's admission
 * limiter should yield fleet-wide.
 *
 * <p>Three signals exist. A 429 ({@link RateLimitRetryException}) means this caller
 * nudged past its own budget - standard pacing, one throttle increment. A 529
 * ({@link OverloadRetryException}) means the provider's fleet is saturated regardless
 * of budgets, and every concurrent job is about to hear the same thing - longer
 * pacing, a harder throttle bump, letting success-driven recovery walk it back. A
 * plain 5xx or connection failure ({@link TransientErrorRetryException}) is a fault,
 * not a slow-down request - standard pacing, no throttle: cutting the fleet's rate
 * over a stray 500 would be an overreaction.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-25)
 */
public abstract sealed class UpstreamRetryException extends RuntimeException
        permits RateLimitRetryException, OverloadRetryException, TransientErrorRetryException {

    protected UpstreamRetryException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * The failure rendered as a diagnosable string: the exception's own message, plus the
     * root cause beneath it when that is a different exception.
     * <p>
     * A transport failure is what forces this. An SDK that wraps an {@link java.io.IOException}
     * reports only its own wrapper text, and the exception underneath it - unknown host,
     * connect timed out, TLS handshake - carries the only fact that separates a broken egress
     * path from a provider fault. Callers persist and log this string and nothing else of the
     * throwable, so a cause missing from here is a cause nobody reading the row can recover.
     *
     * @param e the failure as the provider's client threw it
     * @return the message, followed by the root cause in brackets when there is a distinct one
     */
    public static String details(Throwable e) {
        String own = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        Throwable root = e;
        // A cause chain that points at itself would spin here; badly constructed exceptions do that
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (root == e) {
            return own;
        }
        String rootMessage = root.getMessage();
        return own + " [" + root.getClass().getSimpleName() + (rootMessage != null ? ": " + rootMessage : "") + "]";
    }

    /**
     * Lower edge of the backoff jitter window, in milliseconds, before the
     * dispatcher scales it by the attempt factor.
     */
    public abstract long baseMinJitterMs();

    /** Upper edge of the backoff jitter window, in milliseconds, before attempt scaling. */
    public abstract long baseMaxJitterMs();

    /**
     * How many throttle increments this signal feeds the model's admission limiter
     * ({@link TokenBucketRateLimiter#recordBackpressure(int)}); zero means the
     * limiter does not hear about it.
     */
    public abstract int backpressureIncrements();
}
