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
 * The platform-default heartbeat store: process-local, synchronized, gone with the JVM.
 * Right for fixed-cadence schedules that boot code re-publishes on every start (the
 * store's death loses no decision) and for development; wrong for adaptive self-pacing
 * in production, where the next runAt embodies a decision an Actor made and a restart
 * must not erase it - those deployments plug in a durable store.
 *
 * <p>Entries are indexed twice: by (runAt, heartbeatId) for due-order polling and by
 * heartbeatId for rebind/cancel. Every mutation that can advance the head fires the
 * change listener; the Heart treats any wake as "re-check", so over-notification is
 * harmless by contract.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-29)
 */
public class InMemoryHeartbeatStore implements HeartbeatStore {
    // runAt-ordered with heartbeatId tiebreaker so two entries sharing an instant coexist
    private final TreeMap<InstantAndId, Heartbeat> byDue = new TreeMap<>();
    private final Map<String, Heartbeat> byId = new HashMap<>();
    private volatile Runnable changeListener;

    private record InstantAndId(Instant runAt, String heartbeatId) implements Comparable<InstantAndId> {
        @Override
        public int compareTo(InstantAndId other) {
            int byInstant = runAt.compareTo(other.runAt);
            return byInstant != 0 ? byInstant : heartbeatId.compareTo(other.heartbeatId);
        }
    }

    @Override
    public synchronized Optional<Heartbeat> enqueue(Heartbeat heartbeat) {
        Heartbeat displaced = byId.put(heartbeat.getHeartbeatId(), heartbeat);
        if (displaced != null) {
            byDue.remove(new InstantAndId(displaced.getRunAt(), displaced.getHeartbeatId()));
        }
        byDue.put(new InstantAndId(heartbeat.getRunAt(), heartbeat.getHeartbeatId()), heartbeat);
        notifyChanged();
        return Optional.ofNullable(displaced);
    }

    @Override
    public synchronized Optional<Heartbeat> cancel(String heartbeatId) {
        Heartbeat removed = byId.remove(heartbeatId);
        if (removed != null) {
            byDue.remove(new InstantAndId(removed.getRunAt(), removed.getHeartbeatId()));
            notifyChanged();
        }
        return Optional.ofNullable(removed);
    }

    @Override
    public synchronized Optional<Instant> peekNextRunAt() {
        return byDue.isEmpty() ? Optional.empty() : Optional.of(byDue.firstKey().runAt());
    }

    @Override
    public synchronized Optional<Heartbeat> pollDue(Instant now) {
        if (byDue.isEmpty() || byDue.firstKey().runAt().isAfter(now)) {
            return Optional.empty();
        }
        Map.Entry<InstantAndId, Heartbeat> head = byDue.pollFirstEntry();
        byId.remove(head.getValue().getHeartbeatId());
        return Optional.of(head.getValue());
    }

    @Override
    public synchronized boolean reEnqueueIfAbsent(Heartbeat next) {
        if (byId.containsKey(next.getHeartbeatId())) {
            return false;
        }
        byId.put(next.getHeartbeatId(), next);
        byDue.put(new InstantAndId(next.getRunAt(), next.getHeartbeatId()), next);
        notifyChanged();
        return true;
    }

    @Override
    public synchronized Stream<Heartbeat> inspect() {
        return List.copyOf(byDue.values()).stream();
    }

    @Override
    public void onChange(Runnable listener) {
        this.changeListener = listener;
    }

    private void notifyChanged() {
        Runnable listener = changeListener;
        if (listener != null) {
            listener.run();
        }
    }
}
