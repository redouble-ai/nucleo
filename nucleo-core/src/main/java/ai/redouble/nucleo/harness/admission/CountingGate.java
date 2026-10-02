/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.http.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * The account shape of every unit-permit gate held for the duration of the work: a fixed
 * capacity and an in-use count. Database gates, browser slots, MCP subprocess slots and the
 * shared HTTP connection gate are all this. {@link #earliestFit} is always {@code null}: a
 * permit comes back only when a job gives it back, never with the clock.
 *
 * <p>Takes and gives happen under {@link Admission}'s exclusion; the count is atomic so the
 * health reporter can read it from its own thread.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
public abstract class CountingGate extends AbstractRateLimiter<Void> {

    private final int capacity;
    private final AtomicInteger inUse = new AtomicInteger(0);
    private volatile Instant lastActivity = Instant.now();

    /** A gate of the given capacity; a capacity that is not positive is refused with {@link IllegalArgumentException}. */
    protected CountingGate(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, got " + capacity);
        }
        this.capacity = capacity;
    }

    @Override
    public boolean fits(List<Void> mine, List<Void> reservedAhead) {
        int needed = mine.size();
        if (needed > capacity) {
            throw new UncorrectableRuntimeLLMException(limiterName() + ": a demand of " + needed
                    + " permits can never fit a gate of " + capacity);
        }
        return inUse.get() + needed + reservedAhead.size() <= capacity;
    }

    @Override
    public boolean tryTake(List<Void> mine, List<Void> reservedAhead) {
        int needed = mine.size();
        int reserved = reservedAhead.size();
        if (needed > capacity) {
            throw new UncorrectableRuntimeLLMException(limiterName() + ": a demand of " + needed
                    + " permits can never fit a gate of " + capacity);
        }
        while (true) {
            int current = inUse.get();
            if (current + needed + reserved > capacity) {
                return false;
            }
            if (inUse.compareAndSet(current, current + needed)) {
                lastActivity = Instant.now();
                return true;
            }
        }
    }

    @Override
    public void give(List<Void> amounts) {
        inUse.addAndGet(-amounts.size());
        lastActivity = Instant.now();
    }

    @Override
    public Long earliestFit(List<Void> mine, List<Void> reservedAhead) {
        return null;
    }

    @Override
    public String limiterCategory() {
        return "semaphore";
    }

    @Override
    public long capacity() {
        return capacity;
    }

    @Override
    public long currentInUse() {
        return inUse.get();
    }

    /** Pool admission is not adaptive; there is nothing to learn from a success. */
    @Override
    public void onSuccess() {
    }

    /** Pool admission is not adaptive; upstream feedback does not apply. */
    @Override
    public void onRateLimitError(UpstreamFailure failure) {
    }

    @Override
    public SemaphoreLimiterStatus getStatus() {
        return new SemaphoreLimiterStatus(capacity, capacity - inUse.get(), lastActivity);
    }

    /** The instant of the most recent take or give. */
    public Instant lastActivity() {
        return lastActivity;
    }
}
