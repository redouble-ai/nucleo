/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.observability;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.harness.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The subscriber contract every observer in this package implements: {@code observe} is the
 * one abstract method; the predicate is null by default, meaning every event of the subscribed
 * type; staleness is false by default, so an observer that says nothing is never cleaned up,
 * while one that answers true is reaped by the bus's sweep; and an equal observer subscribed a
 * second time gets a no-op subscription.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class JobObserverContractTest {

    /** An observer whose staleness the test flips, equal to every other instance. */
    static class Flippable implements JobObserver<JobEvent> {
        final List<JobEvent> seen = new ArrayList<>();
        volatile boolean stale;

        @Override
        public void observe(JobEvent event) {
            seen.add(event);
        }

        @Override
        public boolean isStale() {
            return stale;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Flippable;
        }

        @Override
        public int hashCode() {
            return Flippable.class.hashCode();
        }
    }

    @Test
    void aBareObserverAcceptsEveryEventAndIsNeverStale() {
        List<JobEvent> seen = new ArrayList<>();
        JobObserver<JobEvent> observer = seen::add;
        assertNull(observer.getPredicate(), "no predicate means every event of the subscribed type");
        assertFalse(observer.isStale(), "an observer that says nothing about staleness is kept");
        JobEvent event = new JobStartedEvent(ObservabilityFixtures.snapshot("j", "wf", JobState.RUNNING), 1);
        observer.observe(event);
        assertEquals(List.of(event), seen, "observe is the one abstract method, and a lambda is an observer");
    }

    @Test
    void aStaleObserverIsReapedBySweepAndAnEqualOneIsSubscribedOnce() {
        LinkedQueueMessageBus bus = new LinkedQueueMessageBus();
        bus.start();
        try {
            Flippable observer = new Flippable();
            MessageBus.Subscription subscription = bus.subscribe(Job.class, JobEvent.class, observer);
            assertNotEquals("noop", subscription.getId());
            assertEquals("noop", bus.subscribe(Job.class, JobEvent.class, new Flippable()).getId(),
                    "an equal observer subscribed again gets the no-op subscription");
            assertEquals(0, bus.cleanupStaleSubscriptions(), "a fresh observer survives the sweep");
            observer.stale = true;
            assertEquals(1, bus.cleanupStaleSubscriptions(), "the sweep reaps the observer that answers stale");
            assertEquals(0, bus.cleanupStaleSubscriptions(), "and finds nothing the second time");
        }
        finally {
            bus.stop();
        }
    }
}
