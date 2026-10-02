/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors.retry;


/**
 * The provider reported that its fleet is overloaded (Anthropic's 529
 * {@code overloaded_error}): a capacity signal, not a fault and not a budget
 * violation. Handled exactly like a rate limit - transparent retry, fleet-wide
 * throttle - but with a wider wait window (overload episodes last minutes while the
 * refusals fast-fail in seconds, so short waits only burn resource acquisitions) and
 * a harder initial throttle bump (the whole service is drowning and every concurrent
 * job is receiving the same signal; yield hard now, let successes walk it back).
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-25)
 */
public final class OverloadRetryException extends UpstreamRetryException {
    private final String provider;
    private final String errorDetails;

    public OverloadRetryException(String message, String provider, String errorDetails, Throwable cause) {
        super(message, cause);
        this.provider = provider;
        this.errorDetails = errorDetails;
    }

    public String getProvider() {
        return provider;
    }

    public String getErrorDetails() {
        return errorDetails;
    }

    @Override
    public long baseMinJitterMs() {
        return 15000;
    }

    @Override
    public long baseMaxJitterMs() {
        return 90000;
    }

    @Override
    public int backpressureIncrements() {
        return 3;
    }
}
