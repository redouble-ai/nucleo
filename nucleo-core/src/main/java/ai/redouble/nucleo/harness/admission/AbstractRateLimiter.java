/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;

/**
 * Base class for accounts that are their own identity: the wake slot {@link Admission}
 * installs, the {@link Replenishment#RELEASE} default that fits every capacity gate built
 * directly on this class, and the nominal (null) status indicator.
 *
 * <p>Emission and waiter counting are not here. {@link Admission} owns the wait, so it owns
 * the {@link ai.redouble.nucleo.events.LimiterEvent}s that describe it; an account only
 * reports its identity and moves its counters.
 *
 * <p>The two time-based families override {@link #replenishment}:
 * {@link ElasticWindowRateLimiter}, whose override is {@code final} so none of its per-service
 * subclasses can drift, and {@link TokenBucketRateLimiter}. A new limiter whose permits return
 * with the clock rather than with the work must override too.
 *
 * @param <T> the amount type
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-14)
 */
public abstract class AbstractRateLimiter<T> implements RateLimiter<T>, LimiterIdentity {

    private volatile Runnable wake;

    @Override
    public void onCapacityChange(Runnable wake) {
        this.wake = wake;
    }

    /**
     * Runs the installed wake, if any. Subclasses call this after a self-change that can only
     * increase capacity. The wake is a bare unpark and safe to run under any lock.
     */
    protected final void capacityChanged() {
        Runnable w = wake;
        if (w != null) {
            w.run();
        }
    }

    @Override
    public LimiterIdentity accountFor(T amount) {
        return this;
    }

    /**
     * Defaults to {@link Replenishment#RELEASE}, which is what a permit means for the
     * capacity gates built directly on this class - a semaphore slot, a pooled connection,
     * heap headroom. All of those are genuinely free again when the work ends.
     */
    @Override
    public Replenishment replenishment() {
        return Replenishment.RELEASE;
    }

    @Override
    public String statusIndicator() {
        return null;
    }
}
