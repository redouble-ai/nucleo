/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import java.time.*;
import java.util.*;
import java.util.stream.*;

/**
 * Where pending heartbeats live between beats - aliveness as data, not as parked
 * threads. The platform ships only the in-memory default; a durable backing (Postgres,
 * Redis) is an application concern and must satisfy the same contract:
 * <ul>
 *   <li>{@link #enqueue} returns without blocking on durable I/O - the Heart's
 *       subscriber thread is never subjected to storage latency;</li>
 *   <li>{@link #pollDue} is atomic across every process sharing the store - exactly
 *       one Heart gets a given due entry (in-memory: synchronization; Postgres:
 *       {@code SELECT ... FOR UPDATE SKIP LOCKED});</li>
 *   <li>{@link #onChange} delivers cross-process head-of-queue notifications
 *       (in-memory: direct call; Postgres: LISTEN/NOTIFY).</li>
 * </ul>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-29)
 */
public interface HeartbeatStore {

    /**
     * Insert or rebind by heartbeatId (most-recent-wins). Returns the displaced
     * previous entry so the caller can publish the rebind audit event.
     */
    Optional<Heartbeat> enqueue(Heartbeat heartbeat);

    /** Remove and return the entry for heartbeatId, if present. */
    Optional<Heartbeat> cancel(String heartbeatId);

    /** Earliest runAt across all entries - what the Heart's dispatch loop parks until. */
    Optional<Instant> peekNextRunAt();

    /** Atomically remove and return the earliest entry due at or before {@code now}, or empty. Heart-only. */
    Optional<Heartbeat> pollDue(Instant now);

    /**
     * Compare-and-set re-enqueue for recurrence: inserts {@code next} only if no entry
     * with its heartbeatId exists. Returns false when a competing rebind took the slot
     * between poll and re-enqueue - the operator's intent wins over the recurrence.
     */
    boolean reEnqueueIfAbsent(Heartbeat next);

    /** Read-only snapshot for operator queries. */
    Stream<Heartbeat> inspect();

    /**
     * Registers the listener fired when the head of the queue may have changed (an
     * earlier-due entry landed, or the current earliest was cancelled). The Heart wires
     * this to unpark its dispatch thread; spurious wakes are cheap and tolerated.
     */
    void onChange(Runnable listener);
}
