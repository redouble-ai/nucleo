/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;

import ai.redouble.nucleo.harness.*;

import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * A {@link Demand} after {@link Admission} took it: the entries now held, the job snapshot
 * every later event about them carries, and the wait attribution. Settled exactly once, by
 * {@link Admission#release} when the job finishes or {@link Admission#rollback} when the work
 * never happened; a second settlement is a no-op, so an overlapping close and force-close
 * cannot return a permit twice.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
public final class Grant {

    private final Demand demand;
    private final JobSnapshot snapshot;
    private final long wallWaitNanos;
    private final Map<LimiterIdentity, Long> heldNanos;
    private final AtomicBoolean settled = new AtomicBoolean(false);

    Grant(Demand demand, JobSnapshot snapshot, long wallWaitNanos, Map<LimiterIdentity, Long> heldNanos) {
        this.demand = demand;
        this.snapshot = snapshot;
        this.wallWaitNanos = wallWaitNanos;
        this.heldNanos = heldNanos;
    }

    Demand getDemand() {
        return demand;
    }

    JobSnapshot getSnapshot() {
        return snapshot;
    }

    /** Claims the single settlement. */
    boolean settle() {
        return settled.compareAndSet(false, true);
    }

    /** True when the demand had no entries: an orchestrator's grant, which holds nothing. */
    public boolean isEmpty() {
        return demand.isEmpty();
    }

    /** Time from asking to being granted, in milliseconds. */
    public long getWallWaitMs() {
        return wallWaitNanos / 1_000_000L;
    }

    /**
     * Per-account wait attribution: for every account that refused this job at some point
     * while it waited, how long it was short, in milliseconds, keyed by
     * {@link LimiterIdentity#limiterName()}. Accounts overlap in time, so the values do not
     * sum to {@link #getWallWaitMs()}. A read-only view; empty for a job granted at once.
     */
    public Map<String, Long> getWaitTimesMs() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (Map.Entry<LimiterIdentity, Long> e : heldNanos.entrySet()) {
            result.merge(e.getKey().limiterName(), e.getValue() / 1_000_000L, Long::sum);
        }
        return Collections.unmodifiableMap(result);
    }
}
