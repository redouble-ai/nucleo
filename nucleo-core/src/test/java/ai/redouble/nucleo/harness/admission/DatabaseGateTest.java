/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;

import ai.redouble.nucleo.harness.errors.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DatabaseGate} as an admission account: a fixed capacity of held connections, taken and
 * given by the monitor, with no clock deadline of its own. Its event shapes are pinned in
 * {@link AdmissionEventTest} alongside every other account's.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-14)
 */
public class DatabaseGateTest {

    private static final List<Void> NONE = Collections.emptyList();

    private static List<Void> permits(int count) {
        return Collections.nCopies(count, null);
    }

    @Test
    void capacityAndInUse_trackPermits() {
        DatabaseGate gate = new DatabaseGate("default", 2);

        assertEquals(2, gate.capacity());
        assertEquals(0, gate.currentInUse());

        assertTrue(gate.tryTake(permits(1), NONE));
        assertEquals(1, gate.currentInUse());
        assertTrue(gate.tryTake(permits(1), NONE));
        assertEquals(2, gate.currentInUse());
        assertFalse(gate.fits(permits(1), NONE), "a full gate does not fit");

        gate.give(permits(1));
        assertEquals(1, gate.currentInUse());
        assertEquals(2, gate.capacity(), "capacity is fixed regardless of occupancy");
    }

    @Test
    void reservationAheadOfTheHead_countsAgainstTheFollower() {
        DatabaseGate gate = new DatabaseGate("default", 2);
        assertTrue(gate.tryTake(permits(1), NONE));
        assertTrue(gate.fits(permits(1), NONE), "one permit left fits a lone asker");
        assertFalse(gate.fits(permits(1), permits(1)), "with one reserved for the head, the follower does not fit");
        assertFalse(gate.tryTake(permits(1), permits(1)), "and cannot take");
        assertEquals(1, gate.currentInUse());
    }

    @Test
    void aPermitComesBackOnlyThroughGive_neverWithTheClock() {
        DatabaseGate gate = new DatabaseGate("default", 1);
        assertTrue(gate.tryTake(permits(1), NONE));
        assertNull(gate.earliestFit(permits(1), NONE), "no clock instant frees a held connection");
    }

    @Test
    void aDemandBeyondCapacity_isRefused() {
        DatabaseGate gate = new DatabaseGate("default", 2);
        assertThrows(UncorrectableRuntimeLLMException.class, () -> gate.fits(permits(3), NONE));
    }

    @Test
    void name_and_category_identifyTheDatasource() {
        DatabaseGate gate = new DatabaseGate("default", 1);

        assertEquals("db:default", gate.limiterName());
        assertEquals("semaphore", gate.limiterCategory());
        assertEquals(RateLimiter.Replenishment.RELEASE, gate.replenishment());
    }

    @Test
    void constructor_rejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new DatabaseGate("default", 0));
        assertThrows(IllegalArgumentException.class, () -> new DatabaseGate("default", -1));
    }
}
