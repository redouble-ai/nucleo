/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.events;

import ai.redouble.nucleo.events.heartbeat.*;
import ai.redouble.nucleo.events.retry.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import org.junit.jupiter.api.*;

import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;

import static ai.redouble.nucleo.events.EventFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The shape of the event hierarchy: every framework event belongs to exactly one sealed
 * category, each category permits exactly what the package doc's diagram draws, the terminal
 * family is closed and cancellation is not a failure, {@link HumanReadable} marks the events a
 * person can read, the events with no owning job answer a null snapshot, no event exposes a
 * public mutator, and {@link MsgType} is the four phases and the three general values.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class EventCategoryContractTest {

    private static Set<Class<?>> permitsOf(Class<?> sealedType) {
        assertTrue(sealedType.isSealed(), sealedType.getSimpleName() + " is a sealed category");
        return Set.of(sealedType.getPermittedSubclasses());
    }

    @Test
    void theCategoriesPermitExactlyWhatTheCodeSeals() {
        assertEquals(Set.of(JobScheduled.class, JobStartedEvent.class, TerminalEvent.class, OrchestratorResumedEvent.class, OrchestratorIdleEvent.class),
                permitsOf(LifecycleEvent.class), "lifecycle: scheduled, started, the terminal family, the two orchestrator transitions");
        assertEquals(Set.of(AbstractTerminalEvent.class, SuccessEvent.class, FailureEvent.class), permitsOf(TerminalEvent.class));
        assertEquals(Set.of(JobCompletedEvent.class), permitsOf(SuccessEvent.class), "success is completion alone");
        assertEquals(Set.of(JobFailedEvent.class, JobTimedOut.class, DependencyFailureEvent.class), permitsOf(FailureEvent.class),
                "failure: an exception, a timeout, a failed dependency; cancellation is not among them");
        assertEquals(Set.of(JobCompletedEvent.class, JobFailedEvent.class, JobTimedOut.class, JobCancelled.class), permitsOf(AbstractTerminalEvent.class));
        assertEquals(Set.of(WorkflowCompleteEvent.class), permitsOf(WorkflowTerminationEvent.class));
        assertEquals(Set.of(JobProgressEvent.class), permitsOf(ProgressEvent.class));
        assertEquals(Set.of(RateLimitRetryEvent.class, TransientErrorRetryEvent.class, OutputTruncationRetryEvent.class, ResponseCorrectionRetryEvent.class),
                permitsOf(RetryEvent.class));
        assertEquals(Set.of(UserNotificationEvent.class), permitsOf(NotificationEvent.class));
        assertEquals(Set.of(LimiterEvent.class), permitsOf(OperationalEvent.class));
        assertEquals(Set.of(SchedulerEvent.class), permitsOf(SystemEvent.class));
        assertEquals(Set.of(HeartbeatRequested.class, CancelHeartbeat.class, HeartbeatScheduled.class, HeartbeatFired.class, HeartbeatFireFailed.class, HeartbeatRebound.class),
                permitsOf(HeartbeatEvent.class));
        assertFalse(JobEvent.class.isSealed(), "JobEvent itself is open, so an application may add events of its own");
        assertFalse(Modifier.isFinal(JobProgressEvent.class.getModifiers()), "the progress event is open to parametric subclasses");
        assertFalse(JobProgressEvent.class.isSealed());
    }

    @Test
    void everyConcreteFrameworkEventIsFinalExceptTheThreeOpenOnes() {
        List<Class<?>> finals = List.of(JobScheduled.class, JobStartedEvent.class, JobCompletedEvent.class, JobFailedEvent.class, JobTimedOut.class,
                JobCancelled.class, DependencyFailureEvent.class, OrchestratorIdleEvent.class, OrchestratorResumedEvent.class, WorkflowCompleteEvent.class,
                UserNotificationEvent.class, SchedulerEvent.class, RateLimitRetryEvent.class, TransientErrorRetryEvent.class,
                OutputTruncationRetryEvent.class, ResponseCorrectionRetryEvent.class);
        for (Class<?> type : finals) {
            assertTrue(Modifier.isFinal(type.getModifiers()), type.getSimpleName() + " is final: only the platform fires it");
        }
        assertTrue(LimiterEvent.class.isRecord() && Modifier.isFinal(LimiterEvent.class.getModifiers()));
        assertFalse(Modifier.isFinal(ContentStreamEvent.class.getModifiers()), "the stream event stays open, like its parent");
        assertFalse(Modifier.isFinal(MessageCompleteEvent.class.getModifiers()), "the chat completion event is an application-shaped event outside every category");
    }

    @Test
    void messageCompleteIsTheOneEventOutsideEveryCategory() {
        Class<?>[] categories = {LifecycleEvent.class, WorkflowTerminationEvent.class, ProgressEvent.class, RetryEvent.class,
                NotificationEvent.class, OperationalEvent.class, SystemEvent.class, HeartbeatEvent.class};
        assertTrue(JobEvent.class.isAssignableFrom(MessageCompleteEvent.class));
        for (Class<?> category : categories) {
            assertFalse(category.isAssignableFrom(MessageCompleteEvent.class), "MessageCompleteEvent is not a " + category.getSimpleName());
        }
        // Every other concrete event in the three packages is in exactly one category
        Map<Class<?>, Long> memberships = Stream.of(JobScheduled.class, JobStartedEvent.class, JobCompletedEvent.class, JobFailedEvent.class, JobTimedOut.class,
                        JobCancelled.class, DependencyFailureEvent.class, OrchestratorIdleEvent.class, OrchestratorResumedEvent.class, WorkflowCompleteEvent.class,
                        JobProgressEvent.class, ContentStreamEvent.class, UserNotificationEvent.class, LimiterEvent.class, SchedulerEvent.class,
                        RateLimitRetryEvent.class, TransientErrorRetryEvent.class, OutputTruncationRetryEvent.class, ResponseCorrectionRetryEvent.class,
                        HeartbeatRequested.class, CancelHeartbeat.class, HeartbeatScheduled.class, HeartbeatFired.class, HeartbeatFireFailed.class, HeartbeatRebound.class)
                .collect(Collectors.toMap(c -> c, c -> Arrays.stream(categories).filter(cat -> cat.isAssignableFrom(c)).count()));
        memberships.forEach((type, count) -> assertEquals(1L, count, type.getSimpleName() + " belongs to exactly one category"));
    }

    @Test
    void cancellationIsTerminalButNeitherSuccessNorFailure() {
        JobCancelled cancelled = new JobCancelled(snapshot("j", "wf"), false, 1, null, null);
        assertInstanceOf(TerminalEvent.class, cancelled);
        assertInstanceOf(LifecycleEvent.class, cancelled);
        assertFalse(FailureEvent.class.isInstance(cancelled), "a deliberate stop is not a failure to complete");
        assertFalse(SuccessEvent.class.isInstance(cancelled));
        assertEquals(Optional.empty(), cancelled.getCause(), "no failure, so no cause");
    }

    @Test
    void theEventsAPersonCanReadCarryTheMarker() {
        JobSnapshot s = snapshot("j", "wf");
        List<JobEvent> readable = List.of(new JobProgressEvent<>(s, "p"), new ContentStreamEvent(s, new ai.redouble.nucleo.harness.llm.StreamChunk("c", 1, false)),
                new MessageCompleteEvent(s, true), new UserNotificationEvent(s, "t", "n", UserNotificationEvent.Severity.INFO, JobState.RUNNING),
                new OrchestratorIdleEvent(s, List.of(), Map.of()), new OrchestratorResumedEvent(s), new WorkflowCompleteEvent(s),
                new RateLimitRetryEvent(s, ai.redouble.nucleo.harness.admission.RateLimitType.CAPACITY, "m", 1, 0, Duration.ZERO),
                new TransientErrorRetryEvent(s, "p", "d", 1, Duration.ZERO), new OutputTruncationRetryEvent(s, "m", 1, 2),
                new ResponseCorrectionRetryEvent(s, "m", 1, "f"));
        for (JobEvent event : readable) {
            assertInstanceOf(HumanReadable.class, event, event.getClass().getSimpleName() + " is addressed to a person");
            assertNotNull(((HumanReadable) event).getHumanMessage(), event.getClass().getSimpleName() + " has a human message");
        }
        List<JobEvent> notReadable = List.of(new JobScheduled(s), new JobStartedEvent(s, 1), new JobCompletedEvent<>(s, "r", 1, null, null),
                new JobFailedEvent(s, new RuntimeException("x"), 1, null, null), new JobTimedOut(s, Duration.ofSeconds(1), 1, null, null),
                new JobCancelled(s, false, 1, null, null), new DependencyFailureEvent(s, null), new SchedulerEvent(SchedulerEvent.Type.STARTED),
                new LimiterEvent(s, Instant.now(), "x", "semaphore", 1, 1, 0, LimiterEvent.Type.GRANTED_IMMEDIATE, 0, null, null, 1),
                new CancelHeartbeat("hb"));
        for (JobEvent event : notReadable) {
            assertFalse(event instanceof HumanReadable, event.getClass().getSimpleName() + " is telemetry, not addressed to a person");
        }
        // The five heartbeat notifications need a minted Heartbeat to exist; their types are judged directly
        for (Class<?> heartbeat : List.of(HeartbeatRequested.class, HeartbeatScheduled.class, HeartbeatRebound.class, HeartbeatFired.class, HeartbeatFireFailed.class)) {
            assertFalse(HumanReadable.class.isAssignableFrom(heartbeat), heartbeat.getSimpleName() + " is telemetry, not addressed to a person");
        }
    }

    @Test
    void noEventExposesAPublicMutator() {
        List<Class<?>> events = List.of(AbstractJobEvent.class, JobProgressEvent.class, ContentStreamEvent.class, JobScheduled.class, JobStartedEvent.class,
                JobCompletedEvent.class, JobFailedEvent.class, JobTimedOut.class, JobCancelled.class, DependencyFailureEvent.class, MessageCompleteEvent.class,
                UserNotificationEvent.class, OrchestratorIdleEvent.class, OrchestratorResumedEvent.class, WorkflowCompleteEvent.class, SchedulerEvent.class,
                RateLimitRetryEvent.class, TransientErrorRetryEvent.class, OutputTruncationRetryEvent.class, ResponseCorrectionRetryEvent.class);
        List<String> mutators = new ArrayList<>();
        for (Class<?> type : events) {
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers()) && method.getName().startsWith("set")) {
                    mutators.add(type.getSimpleName() + "." + method.getName());
                }
            }
        }
        assertEquals(List.of(), mutators, "an event is immutable once built: no public setter on any event class");
    }

    @Test
    void workflowTerminationAsksItsTwoQuestionsWithoutAnswers() throws NoSuchMethodException {
        assertTrue(Modifier.isAbstract(WorkflowTerminationEvent.class.getMethod("getTerminationReason").getModifiers()),
                "the reason is the member's to answer, never a default");
        assertTrue(Modifier.isAbstract(WorkflowTerminationEvent.class.getMethod("isSuccessful").getModifiers()),
                "the outcome is the member's to answer, never a default");
    }

    @Test
    void theMessageTypesAreTheFourPhasesAndTheThreeGeneralOnes() {
        assertEquals(Set.of("STARTING", "PROGRESSING", "COMPLETING", "FAILING", "STATUS_UPDATE", "MESSAGE", "ERROR"),
                Arrays.stream(MsgType.values()).map(Enum::name).collect(Collectors.toSet()), "seven values, no more");
    }

    @Test
    void eventsWithNoOwningJobAnswerANullSnapshot() {
        assertNull(new SchedulerEvent(SchedulerEvent.Type.STARTED).snapshot(), "a system event has no job");
        assertNull(new CancelHeartbeat("hb").snapshot(), "a heartbeat transition has no owning job");
        LimiterEvent orphan = new LimiterEvent(null, Instant.now(), "memory", "memory", 1, 0, 0, LimiterEvent.Type.RELEASED, 0, null, null, 1);
        assertNull(orphan.snapshot(), "a limiter transition may have no owning job");
        JobSnapshot s = snapshot("j", "wf");
        assertSame(s, new JobStartedEvent(s, 1).snapshot(), "a job event carries the snapshot it was built with");
        assertSame(s, new LimiterEvent(s, Instant.now(), "x", "semaphore", 1, 1, 0, LimiterEvent.Type.GRANTED_IMMEDIATE, 0, null, null, 1).snapshot());
    }

    @Test
    void anEventIsStampedWhenItIsBuilt() {
        Instant before = Instant.now();
        JobStartedEvent event = new JobStartedEvent(snapshot("j", "wf"), 1);
        assertFalse(event.timestamp().isBefore(before), "the timestamp is the construction instant");
        assertFalse(event.timestamp().isAfter(Instant.now()));
    }
}
