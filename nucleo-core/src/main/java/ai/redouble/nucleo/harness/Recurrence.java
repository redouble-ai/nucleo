/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import java.time.*;

/**
 * How a heartbeat repeats. {@link FixedInterval} is the only variant: cron-style
 * calendar semantics (time of day, day of week, timezone, DST) are a separate concern
 * and would be their own record on this sealed interface if a real workload needs them.
 * Slots are fixed from the original anchor: the next fire is {@code runAt + period},
 * never "now + period", so cadence does not drift with dispatch latency.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-29)
 */
public sealed interface Recurrence {

    record FixedInterval(Duration period) implements Recurrence {
        public FixedInterval {
            if (period == null || period.isZero() || period.isNegative()) {
                throw new IllegalArgumentException("A fixed interval requires a positive period");
            }
        }
    }
}
