/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.harness.*;

import java.time.*;
import java.util.*;

/**
 * The one place a {@link LimiterEvent} is built and published. {@link Admission} emits every
 * transition of the accounts it grants, on whichever thread it runs, and no account emits for
 * itself, the memory gate included. Admission does not read a ThreadLocal: the job
 * snapshot travels with the waiter and the grant, so an event emitted on the evaluator thread
 * or on the timeout executor is attributed exactly as one emitted on the job's own thread.
 *
 * <p>Emission is best-effort: an observability failure never breaks admission.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
final class LimiterEvents {

    private LimiterEvents() {
    }

    static void emit(LimiterIdentity account, JobSnapshot snapshot, int waiters, LimiterEvent.Type type, long waitNanos, String rejectReason, long amount) {
        emit(Governor.getMessageBus(), account, snapshot, waiters, type, waitNanos, rejectReason, amount);
    }

    /** The publication itself, over the given bus: nothing with no bus, and a failure to build or publish the event is dropped. */
    static void emit(MessageBus bus, LimiterIdentity account, JobSnapshot snapshot, int waiters, LimiterEvent.Type type, long waitNanos, String rejectReason, long amount) {
        try {
            if (bus == null) {
                return;
            }
            bus.publish(new LimiterEvent(
                    snapshot,
                    Instant.now(),
                    account.limiterName(),
                    account.limiterCategory(),
                    account.capacity(),
                    account.currentInUse(),
                    waiters,
                    type,
                    waitNanos,
                    rejectReason,
                    account.statusIndicator(),
                    amount));
        }
        catch (Throwable t) {
            // Swallowed on purpose: observability is subordinate to correctness (OBSERVABILITY.md).
        }
    }

    /**
     * Units of capacity a list of amounts moves: numbers sum, anything else counts one per
     * amount, so a unit-permit gate reports permits and a token bucket reports tokens.
     */
    static long amountOf(List<?> amounts) {
        long sum = 0;
        for (Object amount : amounts) {
            sum += amount instanceof Number n ? n.longValue() : 1L;
        }
        return sum;
    }
}
