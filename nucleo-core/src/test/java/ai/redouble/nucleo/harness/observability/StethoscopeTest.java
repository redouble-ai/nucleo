/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.observability;

import ai.redouble.nucleo.events.heartbeat.*;
import ai.redouble.nucleo.guardrails.*;
import ai.redouble.nucleo.harness.*;
import org.junit.jupiter.api.*;

import java.lang.reflect.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Stethoscope}: every heartbeat event passes its predicate, each of the six is routed
 * to its own hook and a hook not overridden does nothing; {@code attach} subscribes once on a
 * running dispatcher and refuses a second time, {@code detach} unsubscribes and tolerates
 * being called again.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class StethoscopeTest {

    /** A heartbeat-firable job class for the spec; the heartbeat is never fired here. */
    public static class Beat extends AbstractJob<String> {
        public Beat(Identifiable parent) {
            super(parent, "beat");
        }

        @Override
        public JobRequirements getRequirements() {
            return new JobRequirements();
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            return "beat";
        }
    }

    /** Records which hook each event reached; the ones it does not override stay silent. */
    static class Listening extends Stethoscope {
        final List<String> hooks = new ArrayList<>();

        @Override
        public void onScheduleRequested(HeartbeatRequested e) {hooks.add("requested");}

        @Override
        public void onScheduled(HeartbeatScheduled e) {hooks.add("scheduled");}

        @Override
        public void onCancelled(CancelHeartbeat e) {hooks.add("cancelled");}

        @Override
        public void onRebound(HeartbeatRebound e) {hooks.add("rebound");}

        @Override
        public void onFired(HeartbeatFired e) {hooks.add("fired");}

        @Override
        public void onFireFailed(HeartbeatFireFailed e) {hooks.add("failed");}
    }

    @BeforeAll
    static void startDispatcher() {
        JobDispatcher.getInstance().start();
    }

    /** A heartbeat built through its package-private constructor: the value the six events carry, never fired. */
    private static Heartbeat heartbeat() throws ReflectiveOperationException {
        ScheduleHeartbeat spec = new ScheduleHeartbeat(Beat.class, "stethoscope-" + UUID.randomUUID(), null, Instant.now().plusSeconds(3600), null, null);
        Constructor<Heartbeat> constructor = Heartbeat.class.getDeclaredConstructor(ScheduleHeartbeat.class, Instant.class, String.class, ScopeGuard.class);
        constructor.setAccessible(true);
        return constructor.newInstance(spec, spec.runAt(), "tester", null);
    }

    @Test
    void eachOfTheSixEventsReachesItsOwnHook() throws ReflectiveOperationException {
        Listening watcher = new Listening();
        Heartbeat heartbeat = heartbeat();
        watcher.observe(new HeartbeatRequested(heartbeat));
        watcher.observe(new HeartbeatScheduled(heartbeat));
        watcher.observe(new CancelHeartbeat(heartbeat.getHeartbeatId()));
        watcher.observe(new HeartbeatRebound(heartbeat, heartbeat));
        watcher.observe(new HeartbeatFired(heartbeat, "job-1"));
        watcher.observe(new HeartbeatFireFailed(heartbeat, new IllegalStateException("no carrier")));
        assertEquals(List.of("requested", "scheduled", "cancelled", "rebound", "fired", "failed"), watcher.hooks);
    }

    @Test
    void aHookNotOverriddenDoesNothingAndThePredicateAcceptsEverything() {
        Stethoscope silent = new Stethoscope() {};
        assertDoesNotThrow(() -> silent.observe(new CancelHeartbeat("hb-2")), "the default hook is a no-op");
        assertTrue(silent.getPredicate().test(new CancelHeartbeat("hb-3")), "every heartbeat event is delivered");
    }

    @Test
    void attachSubscribesOnceAndDetachIsIdempotent() {
        Listening watcher = new Listening();
        JobDispatcher dispatcher = JobDispatcher.getInstance();
        watcher.attach(dispatcher);
        try {
            IllegalStateException twice = assertThrows(IllegalStateException.class, () -> watcher.attach(dispatcher));
            assertEquals("Stethoscope already attached", twice.getMessage());
        }
        finally {
            watcher.detach();
        }
        assertDoesNotThrow(watcher::detach, "detaching a detached stethoscope is nothing");
        assertDoesNotThrow(() -> watcher.attach(dispatcher), "detached, it may attach again");
        watcher.detach();
    }
}
