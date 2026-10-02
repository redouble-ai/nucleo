/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import ai.redouble.nucleo.events.heartbeat.*;
import ai.redouble.nucleo.guardrails.*;
import ai.redouble.nucleo.harness.observability.*;
import ai.redouble.nucleo.tools.*;
import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Heart end to end through the real dispatcher: boot-entry scheduling fires under
 * the named principal, an orchestrator's publish is captured with its identity, a
 * non-orchestrator cannot schedule (the door's rule, mirrored), a fire failure keeps a
 * recurrence alive, conversation identity rides the typed setter, cancellation empties the
 * slot, the overdue slots of a recurrence fire back to back one period apart from the
 * anchor, and a stopping Heart drops what it is handed.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-29)
 */
class HeartbeatDispatchTest {

    @BeforeAll
    static void startDispatcher() {
        JobDispatcher.getInstance().start();
    }

    /** A heartbeat-firable job: (Identifiable) ctor, records who ran it, carries conversation identity by declaration. */
    public static class BeatJob extends AbstractJob<String> implements ConversationCarrier {
        static final Map<String, CountDownLatch> LATCHES = new ConcurrentHashMap<>();
        static volatile String lastUserId;
        static volatile String lastConversationId;
        private String conversationId;

        public BeatJob(Identifiable parent) {
            super(parent, "beat");
        }

        @Override
        public void setConversationId(String conversationId) {
            this.conversationId = conversationId;
        }

        @Override
        public JobRequirements getRequirements() {
            return new JobRequirements();
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            lastUserId = context.getUserId();
            lastConversationId = conversationId;
            LATCHES.computeIfAbsent("beat", k -> new CountDownLatch(1)).countDown();
            return context.getUserId();
        }
    }

    /** No (Identifiable) constructor - every fire fails, which is the point. */
    public static class UnfirableJob extends AbstractJob<Void> {
        public UnfirableJob() {
            super(null, "unfirable");
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

    @Test
    void bootEntryFiresUnderTheNamedPrincipal() throws Exception {
        CountDownLatch fired = new CountDownLatch(1);
        BeatJob.LATCHES.put("beat", fired);
        JobDispatcher.getInstance().scheduleHeartbeat(
                new ScheduleHeartbeat(BeatJob.class, "test-boot-" + UUID.randomUUID(), null, Instant.now(), null, null),
                "test-agent");
        assertTrue(fired.await(15, TimeUnit.SECONDS), "the heartbeat fired");
        assertEquals("test-agent", BeatJob.lastUserId, "the fired job runs under the captured principal");
    }

    @Test
    void conversationIdentityRidesTheDeclaredCarrier() throws Exception {
        CountDownLatch fired = new CountDownLatch(1);
        BeatJob.LATCHES.put("beat", fired);
        JobDispatcher.getInstance().scheduleHeartbeat(
                new ScheduleHeartbeat(BeatJob.class, "test-conv-" + UUID.randomUUID(), "conv-42", Instant.now(), null, null),
                "test-agent");
        assertTrue(fired.await(15, TimeUnit.SECONDS));
        assertEquals("conv-42", BeatJob.lastConversationId);
    }

    /** Has a coincidentally-named setter but does NOT declare ConversationCarrier - the fire must refuse it. */
    public static class SetterButNoCarrierJob extends AbstractJob<Void> {
        public SetterButNoCarrierJob(Identifiable parent) {
            super(parent, "no-carrier");
        }

        public void setConversationId(String conversationId) {
            // stores nothing, hydrates nothing - exactly the silent swallow the interface forbids
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

    @Test
    void aConversationIdOnANonCarrierFailsTheFireLoudly() throws Exception {
        CountDownLatch failed = new CountDownLatch(1);
        List<Exception> failures = new CopyOnWriteArrayList<>();
        Stethoscope watcher = new Stethoscope() {
            @Override
            public void onFireFailed(HeartbeatFireFailed e) {
                if (e.heartbeat().getHeartbeatId().startsWith("test-nocarrier")) {
                    failures.add(e.failure());
                    failed.countDown();
                }
            }
        };
        watcher.attach(JobDispatcher.getInstance());
        try {
            JobDispatcher.getInstance().scheduleHeartbeat(
                    new ScheduleHeartbeat(SetterButNoCarrierJob.class, "test-nocarrier-" + UUID.randomUUID(),
                            "conv-orphan", Instant.now(), null, null), "test-agent");
            assertTrue(failed.await(15, TimeUnit.SECONDS), "the fire failed instead of silently dropping continuity");
            assertTrue(failures.get(0).getMessage().contains("ConversationCarrier"), failures.get(0).getMessage());
        }
        finally {
            watcher.detach();
        }
    }

    @Test
    void orchestratorPublishIsCapturedWithItsIdentity() throws Exception {
        CountDownLatch fired = new CountDownLatch(1);
        BeatJob.LATCHES.put("beat", fired);
        AbstractDoer<Void, String> scheduler = new AbstractDoer<>(Job.workflow("doer-principal", "hb-capture")) {
            @Override
            public String execute(JobContext<String> context) {
                context.publish(new ScheduleHeartbeat(BeatJob.class, "test-capture-" + UUID.randomUUID(),
                        null, Instant.now(), null, null));
                return "scheduled";
            }
        };
        JobDispatcher.getInstance().submit(scheduler).get();
        assertTrue(fired.await(15, TimeUnit.SECONDS), "the captured heartbeat fired");
        assertEquals("doer-principal", BeatJob.lastUserId, "identity captured from the publishing orchestrator");
    }

    @Test
    void nonOrchestratorCannotSchedule() {
        AbstractJob<Void> plainJob = new AbstractJob<>(Job.workflow("test-user", "hb-refused"), "plain") {
            @Override
            public JobRequirements getRequirements() {
                return new JobRequirements();
            }

            @Override
            public Void execute(JobResources resources, JobContext<Void> context) {
                context.publish(new ScheduleHeartbeat(BeatJob.class, "forged-" + UUID.randomUUID(),
                        null, Instant.now(), null, null));
                return null;
            }
        };
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> JobDispatcher.getInstance().submit(plainJob).get());
        assertTrue(String.valueOf(failure.getCause().getMessage()).contains("Only orchestrators may schedule"),
                String.valueOf(failure.getCause().getMessage()));
    }

    @Test
    void fireFailureKeepsTheRecurrenceAlive() throws Exception {
        CountDownLatch twoFailures = new CountDownLatch(2);
        Stethoscope watcher = new Stethoscope() {
            @Override
            public void onFireFailed(HeartbeatFireFailed e) {
                if (e.heartbeat().getHeartbeatId().equals("test-recurrence")) {
                    twoFailures.countDown();
                }
            }
        };
        watcher.attach(JobDispatcher.getInstance());
        try {
            JobDispatcher.getInstance().scheduleHeartbeat(
                    new ScheduleHeartbeat(UnfirableJob.class, "test-recurrence", null, Instant.now(), null,
                            new Recurrence.FixedInterval(Duration.ofSeconds(1))), "test-agent");
            assertTrue(twoFailures.await(20, TimeUnit.SECONDS),
                    "the second instance fired despite the first failing - recurrence survived");
        }
        finally {
            JobDispatcher.getInstance().getMessageBus().publish(new CancelHeartbeat("test-recurrence"));
            watcher.detach();
        }
    }

    /** Accepts a typed input payload. */
    public static class InputBeatJob extends AbstractJob<String> {
        static volatile String lastPayload;
        static final CountDownLatch FIRED = new CountDownLatch(1);
        private String payload;

        public InputBeatJob(Identifiable parent) {
            super(parent, "input-beat");
        }

        public void setInput(String payload) {
            this.payload = payload;
        }

        @Override
        public JobRequirements getRequirements() {
            return new JobRequirements();
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            lastPayload = payload;
            FIRED.countDown();
            return payload;
        }
    }

    @Test
    void inputRidesTheTypedSetterAndAMismatchFailsTheFire() throws Exception {
        JobDispatcher.getInstance().scheduleHeartbeat(
                new ScheduleHeartbeat(InputBeatJob.class, "test-input-" + UUID.randomUUID(), null,
                        Instant.now(), "payload-7", null), "test-agent");
        assertTrue(InputBeatJob.FIRED.await(15, TimeUnit.SECONDS));
        assertEquals("payload-7", InputBeatJob.lastPayload);
        // A payload the job cannot accept is a misdeclaration, never a silent drop
        CountDownLatch failed = new CountDownLatch(1);
        List<Exception> failures = new CopyOnWriteArrayList<>();
        Stethoscope watcher = new Stethoscope() {
            @Override
            public void onFireFailed(HeartbeatFireFailed e) {
                if (e.heartbeat().getHeartbeatId().startsWith("test-badinput")) {
                    failures.add(e.failure());
                    failed.countDown();
                }
            }
        };
        watcher.attach(JobDispatcher.getInstance());
        try {
            JobDispatcher.getInstance().scheduleHeartbeat(
                    new ScheduleHeartbeat(InputBeatJob.class, "test-badinput-" + UUID.randomUUID(), null,
                            Instant.now(), 42L, null), "test-agent");
            assertTrue(failed.await(15, TimeUnit.SECONDS), "the mismatched input failed the fire");
            assertTrue(failures.get(0).getMessage().contains("setInput"), failures.get(0).getMessage());
        }
        finally {
            watcher.detach();
        }
    }

    @Test
    void schedulingConfirmsAndRebindingAudits() throws Exception {
        String id = "test-rebind-" + UUID.randomUUID();
        CountDownLatch scheduled = new CountDownLatch(1);
        CountDownLatch rebound = new CountDownLatch(1);
        List<HeartbeatRebound> rebinds = new CopyOnWriteArrayList<>();
        Stethoscope watcher = new Stethoscope() {
            @Override
            public void onScheduled(HeartbeatScheduled e) {
                if (e.heartbeat().getHeartbeatId().equals(id)) {
                    scheduled.countDown();
                }
            }

            @Override
            public void onRebound(HeartbeatRebound e) {
                if (e.current().getHeartbeatId().equals(id)) {
                    rebinds.add(e);
                    rebound.countDown();
                }
            }
        };
        watcher.attach(JobDispatcher.getInstance());
        try {
            Instant first = Instant.now().plusSeconds(3600);
            JobDispatcher.getInstance().scheduleHeartbeat(
                    new ScheduleHeartbeat(BeatJob.class, id, null, first, null, null), "test-agent");
            assertTrue(scheduled.await(15, TimeUnit.SECONDS), "the schedule was confirmed");
            JobDispatcher.getInstance().scheduleHeartbeat(
                    new ScheduleHeartbeat(BeatJob.class, id, null, first.plusSeconds(60), null, null), "test-agent");
            assertTrue(rebound.await(15, TimeUnit.SECONDS), "the rebind was audited");
            assertEquals(first, rebinds.get(0).displaced().getRunAt(), "the audit carries the displaced spec");
        }
        finally {
            JobDispatcher.getInstance().getMessageBus().publish(new CancelHeartbeat(id));
            watcher.detach();
        }
    }

    @Test
    void anOrchestratorCancelsThroughItsOwnPublish() throws Exception {
        String id = "test-orch-cancel-" + UUID.randomUUID();
        JobDispatcher.getInstance().scheduleHeartbeat(
                new ScheduleHeartbeat(BeatJob.class, id, null, Instant.now().plusSeconds(3600), null, null),
                "test-agent");
        HeartbeatStore store = JobDispatcher.getInstance().getHeart().getStore();
        awaitPresent(store, id);
        AbstractDoer<Void, String> canceller = new AbstractDoer<>(Job.workflow("test-user", "hb-cancel")) {
            @Override
            public String execute(JobContext<String> context) {
                context.publish(new CancelHeartbeat(id));
                return "cancelled";
            }
        };
        JobDispatcher.getInstance().submit(canceller).get();
        long deadline = System.currentTimeMillis() + 10000;
        while (store.inspect().anyMatch(hb -> hb.getHeartbeatId().equals(id))) {
            assertTrue(System.currentTimeMillis() < deadline, "the publish-path cancel drained the entry");
            Thread.sleep(20);
        }
    }

    @Test
    void overdueSlotsOfARecurrenceFireBackToBack() throws Exception {
        String id = "test-catchup-" + UUID.randomUUID();
        List<Instant> fires = new CopyOnWriteArrayList<>();
        Stethoscope watcher = new Stethoscope() {
            @Override
            public void onFired(HeartbeatFired e) {
                if (e.heartbeat().getHeartbeatId().equals(id)) {
                    fires.add(e.heartbeat().getRunAt());
                }
            }
        };
        watcher.attach(JobDispatcher.getInstance());
        try {
            Instant anchor = Instant.now().minusMillis(3_500);
            JobDispatcher.getInstance().scheduleHeartbeat(
                    new ScheduleHeartbeat(BeatJob.class, id, null, anchor, null, new Recurrence.FixedInterval(Duration.ofSeconds(1))),
                    "test-agent");
            long deadline = System.currentTimeMillis() + 3_000;
            while (fires.size() < 4 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(fires.size() >= 4, "the four overdue slots fired at once, not one per period: " + fires.size());
            for (int i = 1; i < 4; i++) {
                assertEquals(Duration.ofSeconds(1), Duration.between(fires.get(i - 1), fires.get(i)),
                        "slots are fixed from the anchor, one period apart, never now plus period");
            }
        }
        finally {
            JobDispatcher.getInstance().getMessageBus().publish(new CancelHeartbeat(id));
            watcher.detach();
        }
    }

    @Test
    void aStoppingHeartDropsWhatItIsHanded() {
        InMemoryHeartbeatStore store = new InMemoryHeartbeatStore();
        Heart heart = new Heart(store, JobDispatcher.getInstance());
        heart.stop();
        assertTrue(heart.isStopping());
        heart.observe(new HeartbeatRequested(new Heartbeat(
                new ScheduleHeartbeat(BeatJob.class, "after-stop", null, Instant.now().plusSeconds(60), null, null),
                Instant.now().plusSeconds(60), "test-agent", null)));
        assertTrue(store.inspect().findAny().isEmpty(), "a stopping Heart enqueues nothing");
    }

    @Test
    void theBootEntryDemandsAPrincipal() {
        assertThrows(IllegalArgumentException.class, () -> JobDispatcher.getInstance().scheduleHeartbeat(
                new ScheduleHeartbeat(BeatJob.class, "test-noprincipal", null, Instant.now(), null, null), " "));
    }

    @Test
    void recurrenceCopyKeepsEverythingButTheInstant() {
        ScopeGuard sealed = new ScopeGuard(new TenantScope("t1"));
        Heartbeat original = new Heartbeat(
                new ScheduleHeartbeat(BeatJob.class, "copy-test", "conv-9", Instant.now(), "in",
                        new Recurrence.FixedInterval(Duration.ofHours(6))),
                Instant.now(), "test-agent", sealed);
        Instant shifted = original.getRunAt().plusSeconds(21600);
        Heartbeat next = original.next(shifted);
        assertEquals(shifted, next.getRunAt());
        assertEquals(original.getHeartbeatId(), next.getHeartbeatId());
        assertEquals(original.getJobClass(), next.getJobClass());
        assertEquals(original.getConversationId(), next.getConversationId());
        assertEquals(original.getInput(), next.getInput());
        assertEquals(original.getRecurrence(), next.getRecurrence());
        assertEquals(original.getUserId(), next.getUserId());
        assertSame(sealed, next.getScopeGuard(), "the captured guard survives the recurrence: the next fire is bound as the first was");
    }

    @Test
    void cancelEmptiesTheSlot() throws Exception {
        String id = "test-cancel-" + UUID.randomUUID();
        JobDispatcher.getInstance().scheduleHeartbeat(
                new ScheduleHeartbeat(BeatJob.class, id, null, Instant.now().plusSeconds(3600), null, null),
                "test-agent");
        HeartbeatStore store = JobDispatcher.getInstance().getHeart().getStore();
        awaitPresent(store, id);
        JobDispatcher.getInstance().getMessageBus().publish(new CancelHeartbeat(id));
        long deadline = System.currentTimeMillis() + 10000;
        while (store.inspect().anyMatch(hb -> hb.getHeartbeatId().equals(id))) {
            assertTrue(System.currentTimeMillis() < deadline, "cancellation drained the entry");
            Thread.sleep(20);
        }
    }

    private static void awaitPresent(HeartbeatStore store, String id) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10000;
        while (store.inspect().noneMatch(hb -> hb.getHeartbeatId().equals(id))) {
            assertTrue(System.currentTimeMillis() < deadline, "the schedule landed in the store");
            Thread.sleep(20);
        }
    }

    @Test
    void theHeartsFourNotificationsRenderTheirMessagesFromWhatTheyCarry() {
        Instant at = Instant.now().plusSeconds(60);
        Heartbeat heartbeat = new Heartbeat(new ScheduleHeartbeat(BeatJob.class, "message-shape", null, at, null, null), at, "test-agent", null);
        Heartbeat later = heartbeat.next(at.plusSeconds(60));
        assertEquals("Heartbeat scheduled: " + heartbeat, new HeartbeatScheduled(heartbeat).message());
        assertEquals("Heartbeat rebound: message-shape (was " + heartbeat + ", now " + later + ")", new HeartbeatRebound(heartbeat, later).message());
        HeartbeatFired fired = new HeartbeatFired(heartbeat, "job-9");
        assertEquals("job-9", fired.jobId(), "the run's jobId is the correlation from a schedule to its runs");
        assertEquals("Heartbeat fired: message-shape -> job job-9", fired.message());
        IllegalStateException failure = new IllegalStateException("no carrier");
        assertEquals("Heartbeat fire failed: message-shape - " + failure, new HeartbeatFireFailed(heartbeat, failure).message());
    }
}
