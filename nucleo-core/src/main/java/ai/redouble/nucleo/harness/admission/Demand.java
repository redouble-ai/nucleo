/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;

import ai.redouble.nucleo.harness.*;

import java.util.*;

/**
 * A job's whole resource need: the ordered list of accounts it must hold at once, with the
 * amounts it needs on each. Assembled mechanically from a priced {@link JobRequirements} by
 * {@link JobResources}; {@link Admission} grants it whole or not at all.
 *
 * <p>Entries are normalized by account key, {@link RateLimiter#accountFor}: two bindings that
 * resolve to one model bucket become one entry with two amounts, two EPO inputs on the same
 * service become one entry on that bucket. Nothing in {@link Admission} knows which kinds of
 * account are present or how many; it iterates entries.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
public final class Demand {

    /**
     * One account of the demand: the limiter to call, the identity its amounts debit, and the
     * job's amounts on it.
     *
     * @param <T> the limiter's amount type
     */
    public static final class Entry<T> {
        private final RateLimiter<T> limiter;
        private final LimiterIdentity key;
        private final List<T> amounts = new ArrayList<>();

        Entry(RateLimiter<T> limiter, LimiterIdentity key) {
            this.limiter = limiter;
            this.key = key;
        }

        public RateLimiter<T> getLimiter() {
            return limiter;
        }

        public LimiterIdentity getKey() {
            return key;
        }

        /** The job's amounts on this account; may contain {@code null} for {@code Void}-typed accounts. */
        public List<T> getAmounts() {
            return Collections.unmodifiableList(amounts);
        }
    }

    private final List<Entry<?>> entries = new ArrayList<>();

    /**
     * Adds one amount on one limiter, merging into the existing entry when the same limiter
     * already carries an amount that debits the same account.
     */
    public <T> void add(RateLimiter<T> limiter, T amount) {
        LimiterIdentity key = limiter.accountFor(amount);
        for (Entry<?> existing : entries) {
            if (existing.limiter == limiter && existing.key == key) {
                @SuppressWarnings("unchecked")
                Entry<T> typed = (Entry<T>) existing;
                typed.amounts.add(amount);
                return;
            }
        }
        Entry<T> entry = new Entry<>(limiter, key);
        entry.amounts.add(amount);
        entries.add(entry);
    }

    /** The normalized entries, in the order their first amount was added; a read-only view. */
    public List<Entry<?>> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    /** True for a demand with no entries: an orchestrator's, which holds nothing and never meets the memory gate. */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * This demand's amounts on the given account key, as the amount type of the given entry;
     * empty when the demand does not use that account. Used by {@link Admission} to compute
     * what the head of the queue holds reserved on each account a later waiter asks about.
     */
    <T> List<T> amountsOn(Entry<T> asking) {
        for (Entry<?> entry : entries) {
            if (entry.key == asking.key) {
                @SuppressWarnings("unchecked")
                Entry<T> typed = (Entry<T>) entry;
                return typed.getAmounts();
            }
        }
        return Collections.emptyList();
    }
}
