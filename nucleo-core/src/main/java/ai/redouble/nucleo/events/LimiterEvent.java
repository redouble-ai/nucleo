/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.events;

import ai.redouble.nucleo.harness.*;

import java.time.*;

/**
 * Per-transition event of one admission account. {@code Admission} publishes one for every
 * hold, grant, refusal and release of every account it evaluates, the memory gate included, on
 * whichever thread it runs; no account publishes for itself. The job snapshot travels with the
 * admission waiter and the grant, so attribution needs no ThreadLocal.
 *
 * <p>{@code harness/observability/OBSERVABILITY.md} documents the lifecycle trace shapes, the
 * reject-reason vocabulary and the consumer design; {@code harness/admission/PACKAGE.md} the
 * emission rules. The message renders the transition: {@code <name> HELD (<inUse>/<capacity>,
 * waiters=<n>)}, {@code <name> GRANTED (<inUse>/<capacity>)}, {@code <name> GRANTED after <ms>ms},
 * {@code <name> REJECTED (<reason>)}, {@code <name> RELEASED (<inUse>/<capacity>)}.
 *
 * @param snapshot        the owning job's snapshot (nullable for transitions with no owning job)
 * @param timestamp       event creation time
 * @param limiterName     stable name of the account ("memory", "PubMed", "epo:search", etc.)
 * @param limiterCategory "memory" | "token_bucket" | "elastic_window" | "semaphore"
 * @param capacity        configured capacity of the account (constant per instance, carried for self-describing events)
 * @param inUse           post-transition in-use count
 * @param waiters         post-transition count of jobs held on this account
 * @param type            transition type
 * @param waitNanos       wait duration (populated on GRANTED_FROM_HOLD and REJECTED-after-HELD; 0 otherwise)
 * @param rejectReason    free-form reason string (null unless type is REJECTED; conventional values: cancelled, shutdown, circuit_blocked, aborted)
 * @param statusIndicator short tag describing an abnormal state ({@code "blocked"}, {@code "probing"}, {@code "throttle:2.0x"}, {@code "pace:1500ms"}); null when the account is nominal
 * @param amount          units of capacity moved by this transition: tokens granted on GRANTED_*, requested on HELD/REJECTED, returned on RELEASED. For unit-permit accounts (semaphore, memory gate) this is 1 per permit. Consumers can sum {@code amount} for {@code GRANTED_*} events over a sliding window to compute true throughput, which {@link #inUse} (a bucket-fill snapshot) does not represent.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-14)
 */
public record LimiterEvent(
        JobSnapshot snapshot,
        Instant timestamp,
        String limiterName,
        String limiterCategory,
        long capacity,
        long inUse,
        int waiters,
        Type type,
        long waitNanos,
        String rejectReason,
        String statusIndicator,
        long amount
) implements OperationalEvent {

    public enum Type {
        HELD,
        GRANTED_IMMEDIATE,
        GRANTED_FROM_HOLD,
        REJECTED,
        RELEASED
    }

    @Override
    public String message() {
        return switch (type) {
            case HELD -> limiterName + " HELD (" + inUse + "/" + capacity + ", waiters=" + waiters + ")";
            case GRANTED_IMMEDIATE -> limiterName + " GRANTED (" + inUse + "/" + capacity + ")";
            case GRANTED_FROM_HOLD -> limiterName + " GRANTED after " + (waitNanos / 1_000_000) + "ms";
            case REJECTED -> limiterName + " REJECTED (" + rejectReason + ")";
            case RELEASED -> limiterName + " RELEASED (" + inUse + "/" + capacity + ")";
        };
    }
}
