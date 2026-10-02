/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;
/**
 * Snapshot of a rate limiter's current state for diagnostics and monitoring.
 *
 * <p>Each {@link RateLimiter} implementation returns its own concrete
 * status type carrying the fields that make sense for that limiter (token bucket
 * capacity, sliding window throttle, semaphore permits, heap pressure, ...).
 * Generic callers use {@link #summary()} for a one-line human-readable
 * description; callers that need specific fields pattern-match on the concrete
 * type.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-13)
 */
public interface RateLimiterStatus {
    /**
     * One-line human-readable description of the limiter's current state.
     * Used for logging and monitoring dashboards.
     */
    String summary();
}
