/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the turn inbox handshake: a message offered to a turn is either accepted (and
 * the turn processes it) or refused (and the caller starts a new turn) - never
 * silently lost. The close commits atomically against concurrent offers and is
 * permanent, so there is no window in which two turns both believe they own the
 * conversation's inbox.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-25)
 */
public class ReactiveThinkerInboxTest {

    @Test
    void offersDrainInOrder() {
        ReactiveThinker.Inbox inbox = new ReactiveThinker.Inbox();
        assertTrue(inbox.offer("first"));
        assertTrue(inbox.offer("second"));
        assertEquals("first", inbox.pollOrClose());
        assertEquals("second", inbox.pollOrClose());
    }

    @Test
    void emptyPollCommitsCloseAndOffersAreRefusedForever() {
        ReactiveThinker.Inbox inbox = new ReactiveThinker.Inbox();
        assertNull(inbox.pollOrClose());
        assertFalse(inbox.offer("late"));
        assertNull(inbox.pollOrClose());
        assertFalse(inbox.offer("later still"));
    }

    @Test
    void explicitCloseIsIdempotentAndRefusesOffers() {
        ReactiveThinker.Inbox inbox = new ReactiveThinker.Inbox();
        assertTrue(inbox.offer("queued before close"));
        inbox.close();
        inbox.close();
        assertFalse(inbox.offer("after close"));
        // a message accepted before the close is still delivered, then the poll
        // confirms the committed close with null
        assertEquals("queued before close", inbox.pollOrClose());
        assertNull(inbox.pollOrClose());
    }

    @Test
    void drainForInjectionTakesEverythingWithoutClosing() {
        ReactiveThinker.Inbox inbox = new ReactiveThinker.Inbox();
        assertNull(inbox.drainForInjection());
        assertTrue(inbox.offer("a"));
        assertTrue(inbox.offer("b"));
        List<String> drained = inbox.drainForInjection();
        assertEquals(List.of("a", "b"), drained);
        assertNull(inbox.drainForInjection());
        // the drain must not close the inbox: the exchange is still running
        assertTrue(inbox.offer("c"));
        assertEquals("c", inbox.pollOrClose());
    }

    /**
     * The load-bearing race: offers hammering the inbox while the consumer drains to
     * the committed close. The atomic commit guarantees exactly the accepted messages
     * are polled - an accepted-but-unpolled message would have made pollOrClose return
     * it instead of committing the close, and a post-close offer must be refused.
     */
    @Test
    void racedOfferIsAcceptedOrRefusedNeverLost() throws Exception {
        for (int round = 0; round < 500; round++) {
            ReactiveThinker.Inbox inbox = new ReactiveThinker.Inbox();
            AtomicInteger accepted = new AtomicInteger();
            Thread offerer = new Thread(() -> {
                for (int i = 0; i < 20; i++) {
                    if (inbox.offer("m" + i)) {
                        accepted.incrementAndGet();
                    }
                    else {
                        return;  // closed: every later offer would be refused too
                    }
                }
            });
            offerer.start();
            int polled = 0;
            while (inbox.pollOrClose() != null) {
                polled++;
            }
            offerer.join();
            assertFalse(inbox.offer("straggler"), "the close must be permanent");
            assertEquals(accepted.get(), polled, "every accepted message is polled, every refused one is not");
        }
    }
}
