/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.events;

import ai.redouble.nucleo.events.heartbeat.*;
import org.junit.jupiter.api.*;

import java.time.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The heartbeat events that can be built without a minted {@code Heartbeat}: a cancellation
 * names the id it removes and refuses a blank one, since there is no cancel-all; a schedule
 * request refuses to exist without the heartbeat that carries its authority; and both, like
 * every heartbeat transition, have no owning job.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class HeartbeatEventContractTest {

    @Test
    void aCancellationNamesTheIdAndIsStampedWhenBuilt() {
        Instant before = Instant.now();
        CancelHeartbeat cancel = new CancelHeartbeat("nightly-digest");
        assertEquals("nightly-digest", cancel.heartbeatId());
        assertEquals("Heartbeat cancel requested: nightly-digest", cancel.message());
        assertFalse(cancel.timestamp().isBefore(before));
        assertNull(cancel.snapshot(), "a heartbeat transition has no owning job");
        assertInstanceOf(HeartbeatEvent.class, cancel);
    }

    @Test
    void thereIsNoCancelAll() {
        assertThrows(IllegalArgumentException.class, () -> new CancelHeartbeat(null), "a null id is refused");
        assertThrows(IllegalArgumentException.class, () -> new CancelHeartbeat("  "), "a blank id is refused: nothing cancels every heartbeat");
    }

    @Test
    void aScheduleRequestCannotExistWithoutItsHeartbeat() {
        assertThrows(IllegalArgumentException.class, () -> new HeartbeatRequested(null), "the heartbeat is what carries the authority");
    }
}
