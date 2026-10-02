/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.events;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.llm.*;
import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;

import static ai.redouble.nucleo.events.EventFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * What each job, workflow, notification, limiter and scheduler event carries and says: the
 * message it renders, the {@link MsgType} it maps to, its title, the human message where it has
 * one, and the defaults of the terminal family (empty lists never null, zero duration for a job
 * that never started, no result or error unless the concrete event carries one).
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class EventMessageContractTest {

    private final JobSnapshot s = snapshot("job-1", "wf-1");

    @Test
    void scheduledCarriesItsStateRequirementsAndPriority() {
        JobRequirements requirements = new JobRequirements();
        JobScheduled queued = new JobScheduled(s, JobScheduled.State.QUEUED, requirements, 7);
        assertEquals(JobScheduled.State.QUEUED, queued.getState());
        assertSame(requirements, queued.requirements());
        assertEquals(7, queued.priority());
        JobScheduled accepted = new JobScheduled(s);
        assertEquals(JobScheduled.State.ACCEPTED, accepted.getState(), "the one-argument form is acceptance");
        assertNull(accepted.requirements(), "acceptance carries no requirements yet");
        assertEquals(0, accepted.priority());
    }

    @Test
    void startedNamesTheAttempt() {
        JobStartedEvent started = new JobStartedEvent(s, 3);
        assertEquals(3, started.getAttempt());
        assertEquals("Job started (attempt 3)", started.message());
    }

    @Test
    void completedCarriesTheResultBothWays() {
        JobCompletedEvent<String> done = new JobCompletedEvent<>(s, "answer", 2, null, null);
        assertEquals(Optional.of("answer"), done.getResult());
        assertEquals("answer", done.result());
        assertEquals("Job completed successfully (attempt 2)", done.message());
        assertEquals(2, done.getAttempts());
        JobCompletedEvent<String> empty = new JobCompletedEvent<>(s, null, 1, null, null);
        assertEquals(Optional.empty(), empty.getResult(), "a null result is an empty optional");
        assertNull(empty.result());
    }

    @Test
    void theTerminalFamilyNeverAnswersNullForItsCollections() {
        JobCompletedEvent<String> done = new JobCompletedEvent<>(s, "r", 1, null, null);
        assertEquals(List.of(), done.getLlmResponses(), "no responses is an empty list, never null");
        assertEquals(Map.of(), done.getMetadata(), "no metadata is an empty map, never null");
        List<LLMResponse<?>> responses = List.of();
        Map<String, Object> metadata = Map.of("k", "v");
        JobFailedEvent failed = new JobFailedEvent(s, new RuntimeException("x"), 1, responses, metadata);
        assertSame(responses, failed.getLlmResponses());
        assertSame(metadata, failed.getMetadata());
    }

    @Test
    void terminalTimingComesFromTheSnapshotsStartAndTheEventsOwnInstant() {
        Instant started = Instant.now().minusSeconds(5);
        JobCompletedEvent<String> done = new JobCompletedEvent<>(snapshot("j", "wf", started), "r", 1, null, null);
        assertEquals(started, done.getStartTime());
        assertEquals(done.getTerminatedAt(), done.getCompletionTime(), "completion is the instant the terminal state was reached");
        assertTrue(done.getDuration().compareTo(Duration.ofSeconds(4)) > 0, "duration runs from the start to the terminal instant");
        JobCompletedEvent<String> neverStarted = new JobCompletedEvent<>(snapshot("j", "wf", null), "r", 1, null, null);
        assertEquals(Duration.ZERO, neverStarted.getDuration(), "a job that never started has zero duration");
        assertNull(neverStarted.getStartTime());
    }

    @Test
    void failedNamesTheErrorAndCarriesIt() {
        RuntimeException boom = new RuntimeException("boom");
        JobFailedEvent failed = new JobFailedEvent(s, boom, 1, null, null);
        assertSame(boom, failed.getError());
        assertEquals("Job failed: boom", failed.message());
        assertEquals(Optional.of(boom), failed.getCause(), "the terminal cause is the failure's error");
        assertEquals("Job failed: unknown error", new JobFailedEvent(s, null, 1, null, null).message(), "a failure with no error still renders");
        assertEquals(Optional.empty(), failed.getResult(), "a failure carries no result");
    }

    @Test
    void timedOutNamesTheBudgetAndHasNoError() {
        JobTimedOut timedOut = new JobTimedOut(s, Duration.ofSeconds(90), 2, null, null);
        assertEquals(Duration.ofSeconds(90), timedOut.getTimeout());
        assertEquals("Job timed out after 90 seconds", timedOut.message());
        assertNull(timedOut.getError(), "a timeout is a failure without an exception");
        assertEquals(Optional.empty(), timedOut.getCause());
    }

    @Test
    void cancelledSaysWhetherItWasForced() {
        assertEquals("Job cancelled", new JobCancelled(s, false, 1, null, null).message());
        JobCancelled forced = new JobCancelled(s, true, 1, null, null);
        assertEquals("Job forcefully cancelled", forced.message());
        assertTrue(forced.force());
    }

    @Test
    void dependencyFailureNamesTheErrorClassAndMessage() {
        DependencyFailureEvent failure = new DependencyFailureEvent(s, new IllegalStateException("upstream died"));
        assertEquals("Job failed due to dependency failure: java.lang.IllegalStateException: upstream died", failure.message());
        assertEquals("Job failed due to dependency failure: Unknown", new DependencyFailureEvent(s, null).message());
        assertEquals(List.of(), failure.getLlmResponses(), "a dependency failure carries the interface defaults: no responses");
        assertEquals(started(s), failure.getStartTime());
    }

    private static Instant started(JobSnapshot snapshot) {
        return snapshot.getStartedAt();
    }

    @Test
    void progressMapsItsPercentToALifecyclePhase() {
        assertEquals(MsgType.STATUS_UPDATE, new JobProgressEvent<>(s, "no percent").msgType(), "no percent is a status update");
        assertEquals(MsgType.STARTING, new JobProgressEvent<>(s, "p", 0).msgType());
        assertEquals(MsgType.PROGRESSING, new JobProgressEvent<>(s, "p", 1).msgType());
        assertEquals(MsgType.PROGRESSING, new JobProgressEvent<>(s, "p", 99).msgType());
        assertEquals(MsgType.COMPLETING, new JobProgressEvent<>(s, "p", 100).msgType());
        JobProgressEvent<String> event = new JobProgressEvent<>(s, "payload text", 50);
        assertEquals("payload text", event.message(), "the payload's text form is the message");
        assertEquals("payload text", event.getHumanMessage());
        assertEquals("fixture job", event.title(), "the title is the job's display name");
        assertEquals(JobState.RUNNING, event.jobState());
        assertTrue(event.hasProgress());
        assertFalse(new JobProgressEvent<>(s, "p").hasProgress());
        assertNull(new JobProgressEvent<>(s, null).message(), "a null payload is a null message");
    }

    @Test
    void aStreamChunkIsProgressUntilTheLastOneCompletes() {
        ContentStreamEvent middle = new ContentStreamEvent(s, new StreamChunk("hello", 1, false));
        assertEquals(MsgType.PROGRESSING, middle.msgType());
        assertEquals("Streaming Content", middle.title());
        assertEquals("hello", middle.getHumanMessage(), "the chunk's content is what a person reads");
        assertEquals("Streaming chunk: hello", middle.message());
        assertFalse(middle.hasProgress());
        assertNull(middle.getArtifacts(), "no artifacts is null, never an empty map");
        ContentStreamEvent last = new ContentStreamEvent(s, new StreamChunk(null, 0, true));
        assertEquals(MsgType.COMPLETING, last.msgType());
        assertEquals("Streaming Complete", last.title());
        assertEquals(100, last.getProgressPercent(), "the last chunk is 100%");
        assertEquals("Stream completed", last.getHumanMessage(), "a last chunk without content still reads");
        assertEquals("Streaming complete", last.message());
        assertEquals("Streaming...", new ContentStreamEvent(s, new StreamChunk(null, 0, false)).getHumanMessage());
        String longText = "x".repeat(80);
        assertEquals("Streaming chunk: " + "x".repeat(50), new ContentStreamEvent(s, new StreamChunk(longText, 1, false)).message(),
                "the log message keeps the first fifty characters of the chunk");
        Map<String, Artifact> artifacts = new HashMap<>(Map.of("a", new LinkArtifact()));
        ContentStreamEvent withArtifacts = new ContentStreamEvent(s, new StreamChunk("c", 1, true), artifacts);
        artifacts.clear();
        assertEquals(1, withArtifacts.getArtifacts().size(), "the artifacts are copied at construction");
        assertEquals(1, new ContentStreamEvent(s, new StreamChunk("c", 1, false), Map.of("a", new LinkArtifact())).getArtifacts().size(),
                "a middle chunk carries artifacts too, when given some");
        assertNull(new ContentStreamEvent(s, new StreamChunk("c", 1, true), Map.of()).getArtifacts(), "an empty artifact map reads as none");
        assertSame(withArtifacts.getPayload(), withArtifacts.chunk(), "chunk() is the typed payload");
    }

    @Test
    void messageCompleteSaysWhetherTheExchangeSucceeded() {
        MessageCompleteEvent ok = new MessageCompleteEvent(s, true);
        assertTrue(ok.isSuccessful());
        assertNull(ok.getErrorMessage());
        assertEquals("Message processed", ok.message());
        assertEquals(MsgType.COMPLETING, ok.msgType());
        assertEquals("Message Complete", ok.title());
        assertEquals(JobState.RUNNING, ok.jobState());
        MessageCompleteEvent failed = new MessageCompleteEvent(s, false, "model refused");
        assertEquals("model refused", failed.getErrorMessage());
        assertEquals("Message processing failed", failed.message());
        assertEquals(MsgType.FAILING, failed.msgType());
        assertEquals("Message Failed", failed.title());
        assertEquals(failed.message(), failed.getHumanMessage());
    }

    @Test
    void aNotificationMapsItsSeverityAndTheFactoriesReadTheStateOffTheSnapshot() {
        assertEquals(MsgType.ERROR, UserNotificationEvent.error(s, "t", "m").msgType());
        assertEquals(MsgType.COMPLETING, UserNotificationEvent.success(s, "t", "m").msgType());
        assertEquals(MsgType.MESSAGE, UserNotificationEvent.warning(s, "t", "m").msgType());
        assertEquals(MsgType.MESSAGE, UserNotificationEvent.info(s, "t", "m").msgType());
        UserNotificationEvent info = UserNotificationEvent.info(s, "Found duplicates", "review recommended");
        assertEquals(UserNotificationEvent.Severity.INFO, info.getSeverity());
        assertEquals("Found duplicates", info.title());
        assertEquals("review recommended", info.getHumanMessage());
        assertEquals("review recommended", info.message());
        assertEquals(JobState.RUNNING, info.jobState(), "a factory takes the state from the snapshot");
        assertNull(info.getDetails(), "no structured details unless given");
        Object details = Map.of("count", 3);
        UserNotificationEvent full = new UserNotificationEvent(s, "t", "n", UserNotificationEvent.Severity.WARNING, JobState.QUEUED, details);
        assertSame(details, full.getDetails());
        assertEquals(JobState.QUEUED, full.jobState(), "the full constructor takes the state it is given");
    }

    @Test
    void theOrchestratorTransitionsSayReadyAndProcessing() {
        List<LLMResponse<?>> responses = List.of();
        Map<String, Object> metadata = Map.of();
        OrchestratorIdleEvent idle = new OrchestratorIdleEvent(s, responses, metadata);
        assertEquals("Ready", idle.message());
        assertEquals("Ready", idle.getHumanMessage());
        assertSame(responses, idle.getLlmResponses());
        assertSame(metadata, idle.getMetadata());
        OrchestratorResumedEvent resumed = new OrchestratorResumedEvent(s);
        assertEquals("Processing", resumed.message());
        assertEquals("Processing", resumed.getHumanMessage());
    }

    @Test
    void workflowCompleteCarriesTheOutcome() {
        WorkflowCompleteEvent ok = new WorkflowCompleteEvent(s);
        assertTrue(ok.isSuccessful());
        assertEquals("Workflow completed successfully", ok.getReason());
        assertEquals(ok.getReason(), ok.getTerminationReason());
        assertEquals(ok.getReason(), ok.message());
        assertEquals(MsgType.COMPLETING, ok.msgType());
        assertEquals("Workflow Complete", ok.title());
        WorkflowCompleteEvent failed = new WorkflowCompleteEvent(s, false, "root job failed");
        assertFalse(failed.isSuccessful());
        assertEquals("root job failed", failed.getTerminationReason());
        assertEquals(MsgType.FAILING, failed.msgType());
        assertEquals("Workflow Failed", failed.title());
        assertEquals(JobState.RUNNING, failed.jobState());
        assertEquals(failed.message(), failed.getHumanMessage());
    }

    @Test
    void aSchedulerEventCarriesItsTypeAndDefaultMessage() {
        SchedulerEvent started = new SchedulerEvent(SchedulerEvent.Type.STARTED);
        assertEquals(SchedulerEvent.Type.STARTED, started.getType());
        assertEquals("Job scheduler started", started.message());
        assertNull(started.getError());
        assertEquals("Job scheduler stopping", new SchedulerEvent(SchedulerEvent.Type.STOPPING).message());
        assertEquals("Job scheduler stopped", new SchedulerEvent(SchedulerEvent.Type.STOPPED).message());
        assertEquals("custom", new SchedulerEvent(SchedulerEvent.Type.STOPPED, "custom").message(), "a custom message replaces the default");
        RuntimeException boom = new RuntimeException("disk gone");
        SchedulerEvent error = new SchedulerEvent(boom);
        assertEquals(SchedulerEvent.Type.ERROR, error.getType());
        assertSame(boom, error.getError());
        assertEquals("Scheduler error: disk gone", error.message());
        assertEquals("Scheduler error: Unknown", new SchedulerEvent((Throwable) null).message());
    }

    @Test
    void aLimiterEventRendersEachTransitionInItsOwnShape() {
        assertEquals("pubmed HELD (3/3, waiters=2)", limiter(LimiterEvent.Type.HELD, 3, 2, 0, null).message());
        assertEquals("pubmed GRANTED (1/3)", limiter(LimiterEvent.Type.GRANTED_IMMEDIATE, 1, 0, 0, null).message());
        assertEquals("pubmed GRANTED after 1500ms", limiter(LimiterEvent.Type.GRANTED_FROM_HOLD, 1, 0, 1_500_000_000L, null).message());
        assertEquals("pubmed REJECTED (circuit_blocked)", limiter(LimiterEvent.Type.REJECTED, 0, 0, 0, "circuit_blocked").message());
        assertEquals("pubmed RELEASED (0/3)", limiter(LimiterEvent.Type.RELEASED, 0, 0, 0, null).message());
    }

    private LimiterEvent limiter(LimiterEvent.Type type, long inUse, int waiters, long waitNanos, String reason) {
        return new LimiterEvent(s, Instant.now(), "pubmed", "elastic_window", 3, inUse, waiters, type, waitNanos, reason, null, 1);
    }
}
