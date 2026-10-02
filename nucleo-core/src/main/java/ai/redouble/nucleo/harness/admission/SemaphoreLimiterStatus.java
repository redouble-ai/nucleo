/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;

import java.time.*;

/**
 * Status snapshot for a semaphore-based concurrency limiter.
 *
 * <p>Reports the number of permits available out of the maximum, plus the
 * timestamp of the most recent activity. Used by limiters that cap concurrent
 * access to a shared resource (for example, MCP STDIO subprocesses or any
 * other concurrency pool).
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-13)
 */
public record SemaphoreLimiterStatus(
        int maxConcurrent,
        int availablePermits,
        Instant lastActivity
) implements RateLimiterStatus {
    /** Permits in use: the maximum less the available. */
    public int activeCount() {
        return maxConcurrent - availablePermits;
    }

    @Override
    public String summary() {
        return String.format("permits %d/%d active, last activity %s",
                activeCount(), maxConcurrent, lastActivity);
    }
}
