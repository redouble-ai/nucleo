/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;
/**
 * Status snapshot for the memory-pressure gate: heap occupancy, the floor, which is the reading
 * after the most recent collection the gate saw and the number the growth penalty measures, the
 * zone the gate is in ({@code CRITICAL} while latched, {@code DRAINING} while memory's line is
 * non-empty, {@code GREEN} otherwise), how many jobs admission is holding on it, which is memory's line, how long
 * until the next release is due if a completion does not come first, and the growth penalty
 * multiplying that wait. Jobs waiting on memory are also reported by the {@code LimiterEvent}
 * stream, whose {@code waiters} field {@link Admission} maintains.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-13)
 */
public record MemoryPressureStatus(
        long heapUsedBytes,
        long heapMaxBytes,
        double usageRatio,
        double floorRatio,
        String zone,
        int heldOnMemory,
        long pacingDelayMillis,
        double growthPenalty
) implements RateLimiterStatus {
    @Override
    public String summary() {
        double gb = 1024.0 * 1024.0 * 1024.0;
        return String.format("%.1f%% heap (%.1fG / %.1fG), floor %.1f%%, zone %s, %d in memory's line, next release in %dms unless a job finishes first, growth penalty x%.0f",
                usageRatio * 100, heapUsedBytes / gb, heapMaxBytes / gb, floorRatio * 100, zone, heldOnMemory, pacingDelayMillis, growthPenalty);
    }
}
