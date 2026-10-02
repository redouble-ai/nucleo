/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;
import static ai.redouble.nucleo.harness.admission.AdmissionFixtures.*;

/**
 * {@link Demand} and {@link Grant} as a caller sees them: entries keep the order their first
 * amount was added and merge by account; a fast-path grant attributes no wait; a grant that
 * parked attributes its wait to the account that kept it, keyed by the account's name, and its
 * wall wait is asking to granted.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class GrantAndDemandTest {

    private final MemoryFake memory = new MemoryFake();
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private Admission admission;

    @BeforeEach
    void start() {
        admission = new Admission(memory, clock::get, (a, s, w, t, wait, r, amt) -> { });
        admission.start();
    }

    @AfterEach
    void stop() {
        admission.stop();
    }

    @Test
    void entriesKeepTheOrderTheirFirstAmountWasAdded_andMergeByAccount() {
        Meter meter = new Meter("meter", 1_000, 0, clock::get);
        Slots slot = new Slots("slot", 1);
        Demand demand = new Demand();
        assertTrue(demand.isEmpty(), "nothing added yet");
        demand.add(meter, 100);
        demand.add(slot, null);
        demand.add(meter, 50);
        assertFalse(demand.isEmpty());
        assertEquals(2, demand.getEntries().size(), "two accounts, two entries");
        assertSame(meter, demand.getEntries().get(0).getLimiter(), "the meter came first");
        assertEquals(List.of(100, 50), demand.getEntries().get(0).getAmounts(), "its second amount merged into the first entry");
        assertSame(slot, demand.getEntries().get(1).getLimiter());
        assertThrows(UnsupportedOperationException.class, () -> demand.getEntries().clear(), "the entries are read-only");
    }

    @Test
    void aFastPathGrantAttributesNoWait() throws InterruptedException {
        Slots slot = new Slots("slot", 1);
        Grant grant = admission.admit(demand(slot), context());
        assertFalse(grant.isEmpty());
        assertEquals(0, grant.getWallWaitMs(), "granted at once on a still clock");
        assertTrue(grant.getWaitTimesMs().isEmpty(), "no account kept it");
        assertTrue(admission.admit(new Demand(), context()).isEmpty(), "an orchestrator's grant is empty");
    }

    @Test
    void aParkedGrantAttributesItsWaitToTheAccountThatKeptIt() throws Exception {
        Slots slot = new Slots("slot", 1);
        Grant holder = admission.admit(demand(slot), context());
        AtomicReference<Grant> out = new AtomicReference<>();
        Thread waiter = Thread.ofVirtual().start(() -> {
            try {
                out.set(admission.admit(demand(slot), context()));
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(await(() -> admission.queueSize() == 1, 5_000));
        clock.addAndGet(TimeUnit.SECONDS.toNanos(2));
        admission.release(holder);
        waiter.join(5_000);
        Grant granted = out.get();
        assertNotNull(granted);
        assertEquals(2_000, granted.getWallWaitMs(), "two seconds from asking to granted on the shared clock");
        assertEquals(Map.of("slot", 2_000L), granted.getWaitTimesMs(), "the slot kept it for the whole wait, keyed by the account's name");
        assertThrows(UnsupportedOperationException.class, () -> granted.getWaitTimesMs().clear(), "the attribution is read-only");
    }

    @Test
    void accountsThatKeptAJobAtTheSameTime_eachGetTheWholeOverlap() throws Exception {
        Slots first = new Slots("first", 1);
        Slots second = new Slots("second", 1);
        Grant firstHolder = admission.admit(demand(first), context());
        Grant secondHolder = admission.admit(demand(second), context());
        AtomicReference<Grant> out = new AtomicReference<>();
        Thread waiter = Thread.ofVirtual().start(() -> {
            try {
                out.set(admission.admit(demand(first, second), context()));
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(await(() -> admission.queueSize() == 1, 5_000));
        clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
        admission.release(firstHolder);
        assertFalse(await(() -> out.get() != null, 200), "still short on the second account");
        clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
        admission.release(secondHolder);
        waiter.join(5_000);
        Grant granted = out.get();
        assertNotNull(granted);
        assertEquals(2_000, granted.getWallWaitMs());
        assertEquals(Map.of("first", 2_000L, "second", 2_000L), granted.getWaitTimesMs(),
                "both accounts refused it at arrival and are credited until the grant, so the attribution sums past the wall wait");
    }
}
