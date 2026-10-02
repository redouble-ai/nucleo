/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import org.junit.jupiter.api.*;

import java.time.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The in-memory store's contract: due-ordered polling, most-recent-wins rebind,
 * cancellation, the recurrence CAS yielding to operator rebinds, and head-change
 * notification.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-29)
 */
class HeartbeatStoreTest {

    private static class NopJob extends AbstractJob<Void> {
        public NopJob(Identifiable parent) {
            super(parent, "nop");
        }

        @Override
        public JobRequirements getRequirements() {
            return new JobRequirements();
        }

        @Override
        public Void execute(JobResources resources, JobContext<Void> context) {
            return null;
        }
    }

    private static Heartbeat heartbeat(String id, Instant runAt) {
        return new Heartbeat(new ScheduleHeartbeat(NopJob.class, id, null, runAt, null, null), runAt, "test-user", null);
    }

    @Test
    void pollDueRespectsOrderAndDueness() {
        InMemoryHeartbeatStore store = new InMemoryHeartbeatStore();
        Instant now = Instant.now();
        store.enqueue(heartbeat("later", now.plusSeconds(3600)));
        store.enqueue(heartbeat("sooner", now.minusSeconds(10)));
        store.enqueue(heartbeat("soonest", now.minusSeconds(60)));
        assertEquals("soonest", store.pollDue(now).orElseThrow().getHeartbeatId());
        assertEquals("sooner", store.pollDue(now).orElseThrow().getHeartbeatId());
        assertTrue(store.pollDue(now).isEmpty(), "the future entry is not due");
        assertEquals(now.plusSeconds(3600), store.peekNextRunAt().orElseThrow());
    }

    @Test
    void rebindDisplacesAndCancelRemoves() {
        InMemoryHeartbeatStore store = new InMemoryHeartbeatStore();
        Instant now = Instant.now();
        assertTrue(store.enqueue(heartbeat("x", now)).isEmpty());
        Heartbeat displaced = store.enqueue(heartbeat("x", now.plusSeconds(100))).orElseThrow();
        assertEquals(now, displaced.getRunAt(), "most-recent-wins returns the displaced spec");
        assertEquals(1, store.inspect().count());
        assertEquals("x", store.cancel("x").orElseThrow().getHeartbeatId());
        assertTrue(store.inspect().findAny().isEmpty());
        assertTrue(store.cancel("x").isEmpty(), "cancelling the absent is empty, not an error");
    }

    @Test
    void recurrenceCasYieldsToACompetingRebind() {
        InMemoryHeartbeatStore store = new InMemoryHeartbeatStore();
        Instant now = Instant.now();
        Heartbeat polled = heartbeat("r", now);
        // Operator rebind lands between poll and re-enqueue
        Heartbeat rebind = heartbeat("r", now.plusSeconds(30));
        store.enqueue(rebind);
        assertFalse(store.reEnqueueIfAbsent(polled.next(now.plusSeconds(3600))), "the operator's intent stands");
        assertEquals(now.plusSeconds(30), store.inspect().findFirst().orElseThrow().getRunAt());
        store.cancel("r");
        assertTrue(store.reEnqueueIfAbsent(polled.next(now.plusSeconds(3600))), "an empty slot accepts the recurrence");
    }

    @Test
    void headChangesFireTheListener() {
        InMemoryHeartbeatStore store = new InMemoryHeartbeatStore();
        AtomicInteger wakes = new AtomicInteger();
        store.onChange(wakes::incrementAndGet);
        store.enqueue(heartbeat("a", Instant.now().plusSeconds(60)));
        store.cancel("a");
        assertEquals(2, wakes.get());
    }

    @Test
    void bareScheduleHeartbeatValidates() {
        assertThrows(IllegalArgumentException.class,
                () -> new ScheduleHeartbeat(null, "id", null, Instant.now(), null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ScheduleHeartbeat(NopJob.class, " ", null, Instant.now(), null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ScheduleHeartbeat(NopJob.class, "id", null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new Recurrence.FixedInterval(Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new Heartbeat(new ScheduleHeartbeat(NopJob.class, "id", null, Instant.now(), null, null),
                        Instant.now(), " ", null));
    }
}
