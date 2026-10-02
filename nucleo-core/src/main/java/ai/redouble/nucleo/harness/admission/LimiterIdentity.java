/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;

/**
 * The observable identity of one admission account: what the health snapshot, the
 * {@link ai.redouble.nucleo.events.LimiterEvent} stream and the span and meter customizers
 * name a row after. Every {@link RateLimiter} resolves each amount it is asked about to one
 * of these through {@link RateLimiter#accountFor}; for a plain limiter that is itself, for a
 * router it is the bucket the amount debits.
 *
 * <p>The accessors sit on the hot path of every emitted event and must be cheap: no refills,
 * no cleanups, no heavy work.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
public interface LimiterIdentity {
    /** Stable display name for this account. Used as a metric tag, a log prefix and the {@code wait_times} key. */
    String limiterName();

    /** Category identifier. One of: "memory", "token_bucket", "elastic_window", "semaphore". */
    String limiterCategory();

    /** Configured capacity of this account. */
    long capacity();

    /** Current in-use count. */
    long currentInUse();

    /**
     * Short human-readable tag describing an abnormal operational state of this account:
     * {@code "blocked"}, {@code "probing"}, {@code "throttle:2.0x"}, {@code "pace:1500ms"}.
     * Returns {@code null} when the account is in its nominal state. Consumers surface it
     * alongside capacity and in-use so an out-of-sorts account is visible even when its
     * numeric columns look calm.
     */
    String statusIndicator();

    /** Implementation-specific status snapshot. */
    RateLimiterStatus getStatus();
}
