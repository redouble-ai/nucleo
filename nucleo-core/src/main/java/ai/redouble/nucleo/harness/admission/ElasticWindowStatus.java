/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;
/**
 * Status snapshot for a sliding-window rate limiter with adaptive throttling.
 *
 * <p>Reports current semaphore availability, throttle coefficient, and the
 * effective window size after throttle stretching.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-13)
 */
public record ElasticWindowStatus(
        int maxRequests,
        int availablePermits,
        double throttleCoefficient,
        long effectiveWindowMs,
        String circuitState
) implements RateLimiterStatus {
    @Override
    public String summary() {
        return String.format("permits %d/%d, throttle %.1f, window %dms, circuit %s",
                availablePermits, maxRequests, throttleCoefficient, effectiveWindowMs, circuitState);
    }
}
