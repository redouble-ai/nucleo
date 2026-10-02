/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.observability;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.events.retry.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.*;
import ch.qos.logback.core.read.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;

import static ai.redouble.nucleo.harness.observability.ObservabilityFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link EventLogger}: operational events are excluded by the predicate; every other event is
 * one line on the logger of the class it is about - the job's own class, or the event's class
 * when there is no job - plus a segment naming the kind of event, at that kind's fixed level,
 * so progress and stream chunks stay out of an INFO log until the job's class or the class
 * plus the segment is lowered; the workflow id rides the MDC while the lines are written; a
 * system event reads {@code [SYSTEM]} and its message, a job event the shortened workflow id,
 * {@code [jobId/STATE]} and the message; a failure whose error is neither LLM-readable nor a
 * wrapped child failure adds its stack trace at ERROR; and every instance is equal to every
 * other, so a bus holds one.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class EventLoggerTest {

    /** The class the fixture snapshots name, and so the logger their events land on. */
    private static final String JOB_LOGGER = Job.class.getName();

    /** A job class of its own, to prove a line is attributed to the job and not to a constant. */
    static final class Ingest extends AbstractJob<String> {
        Ingest(Identifiable parent) {
            super(parent, "ingest");
        }

        @Override
        public JobRequirements getRequirements() {
            return new JobRequirements();
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            return "ingested";
        }
    }

    /** What the named logger received while the body ran. */
    private List<ILoggingEvent> linesOf(String loggerName, Runnable body) {
        ListAppender<ILoggingEvent> appender = ObservabilityFixtures.capture(loggerName);
        try {
            body.run();
            return List.copyOf(appender.list);
        }
        finally {
            ObservabilityFixtures.release(loggerName, appender);
        }
    }

    @Test
    void thePredicateExcludesOperationalEventsAndKeepsTheRest() {
        EventLogger logger = new EventLogger();
        JobSnapshot snapshot = snapshot("j", "wf", JobState.RUNNING);
        assertFalse(logger.getPredicate().test(limiter(snapshot, "memory", "memory", 10, 1, 0, LimiterEvent.Type.HELD, 0, null, null, 1)),
                "a limiter transition is the health reporter's to render");
        assertTrue(logger.getPredicate().test(new JobStartedEvent(snapshot, 1)), "a lifecycle event is logged");
        assertTrue(logger.getPredicate().test(new JobProgressEvent<>(snapshot, "half way", 50)), "a progress event is logged");
    }

    /** What the named logger received while the body ran, with that logger set to the level for the duration. */
    private List<ILoggingEvent> linesAt(String loggerName, Level level, Runnable body) {
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(loggerName);
        Level before = logger.getLevel();
        logger.setLevel(level);
        try {
            return linesOf(loggerName, body);
        }
        finally {
            logger.setLevel(before);
        }
    }

    @Test
    void everyLineLandsOnTheLoggerOfTheClassItIsAboutPlusItsKind() {
        JobSnapshot ingesting = snapshotOf(Ingest.class, "job-3", "wf", "wf", JobType.TOOL, JobState.RUNNING, "job-3");
        List<ILoggingEvent> onTheJob = linesOf(Ingest.class.getName(), () -> new EventLogger().observe(new JobStartedEvent(ingesting, 1)));
        assertEquals(1, onTheJob.size(), "a job event is attributed to the job's own class, so a layout naming the logger names the job");
        assertEquals(Ingest.class.getName() + ".lifecycle", onTheJob.get(0).getLoggerName(), "one segment deeper, naming the kind of event");
        List<ILoggingEvent> onTheEvent = linesOf(SchedulerEvent.class.getName(),
                () -> new EventLogger().observe(new SchedulerEvent(SchedulerEvent.Type.STARTED, "scheduler up")));
        assertEquals(1, onTheEvent.size(), "a system event has no job, so the event's own class is what is left of the origin");
        assertEquals(SchedulerEvent.class.getName() + ".system", onTheEvent.get(0).getLoggerName());
    }

    @Test
    void eachKindOfEventLandsOnItsSegmentAtItsLevel() {
        JobSnapshot ingesting = snapshotOf(Ingest.class, "job-4", "wf", "wf", JobType.TOOL, JobState.RUNNING, "job-4");
        EventLogger logger = new EventLogger();
        List<ILoggingEvent> lines = linesAt(Ingest.class.getName(), Level.DEBUG, () -> {
            logger.observe(new JobFailedEvent(ingesting, new InvalidInputException("q", "", "must not be blank"), 1, List.of(), Map.of()));
            logger.observe(new JobStartedEvent(ingesting, 1));
            logger.observe(new TransientErrorRetryEvent(ingesting, "bedrock", "503", 1, java.time.Duration.ofSeconds(1)));
            logger.observe(new WorkflowCompleteEvent(ingesting));
            logger.observe(UserNotificationEvent.info(ingesting, "Tool Completed", "fine"));
            logger.observe(UserNotificationEvent.success(ingesting, "Done", "fine"));
            logger.observe(UserNotificationEvent.warning(ingesting, "Careful", "odd"));
            logger.observe(UserNotificationEvent.error(ingesting, "Tool Failed", "broken"));
            logger.observe(new ContentStreamEvent(ingesting, StreamChunk.of("tok")));
            logger.observe(new JobProgressEvent<>(ingesting, "half way", 50));
        });
        String job = Ingest.class.getName();
        List<String> expected = List.of(
                job + ".failure WARN", job + ".lifecycle INFO", job + ".retry DEBUG", job + ".workflow INFO",
                job + ".notification DEBUG", job + ".notification DEBUG", job + ".notification WARN", job + ".notification ERROR",
                job + ".stream DEBUG", job + ".progress DEBUG");
        assertEquals(expected, lines.stream().map(line -> line.getLoggerName() + " " + line.getLevel()).toList(),
                "each kind of event has its segment and its fixed level; an INFO or SUCCESS notification is a running job's narration, at DEBUG, a warning or an error keeps its severity");
    }

    @Test
    void progressAndStreamStayOutOfAnInfoLogAndTheJobsClassBringsThemBack() {
        JobSnapshot ingesting = snapshotOf(Ingest.class, "job-5", "wf", "wf", JobType.TOOL, JobState.RUNNING, "job-5");
        Runnable chatter = () -> {
            new EventLogger().observe(new JobProgressEvent<>(ingesting, "parsing", 10));
            new EventLogger().observe(new ContentStreamEvent(ingesting, StreamChunk.of("tok")));
            new EventLogger().observe(new JobStartedEvent(ingesting, 1));
        };
        List<ILoggingEvent> atInfo = linesOf(Ingest.class.getName(), chatter);
        assertEquals(List.of(Ingest.class.getName() + ".lifecycle"), atInfo.stream().map(ILoggingEvent::getLoggerName).toList(),
                "under the runtime's INFO only the lifecycle line is written");
        List<ILoggingEvent> atDebug = linesAt(Ingest.class.getName(), Level.DEBUG, chatter);
        assertEquals(3, atDebug.size(), "the job's class at DEBUG brings back all of its events");
        List<ILoggingEvent> oneKind = linesAt(Ingest.class.getName() + ".progress", Level.DEBUG, chatter);
        assertEquals(List.of(Ingest.class.getName() + ".progress"), oneKind.stream().map(ILoggingEvent::getLoggerName).toList(),
                "the class plus a segment brings back that one kind");
    }

    @Test
    void theWorkflowIdRidesTheMdcWhileTheLinesAreWritten() {
        JobSnapshot snapshot = snapshot("job-6", "wf-42", JobState.FAILED);
        RuntimeException raw = new RuntimeException("disk gone");
        List<ILoggingEvent> lines = linesOf(JOB_LOGGER, () -> new EventLogger().observe(new JobFailedEvent(snapshot, raw, 1, List.of(), Map.of())));
        assertEquals(2, lines.size(), "the event line, then the trace");
        for (ILoggingEvent line : lines) {
            assertEquals("wf-42", line.getMDCPropertyMap().get(EventLogger.MDC_WORKFLOW_ID), "every line of the event carries its workflow id");
        }
        assertNull(org.slf4j.MDC.get(EventLogger.MDC_WORKFLOW_ID), "and the id is gone once the event is written");
        List<ILoggingEvent> system = linesOf(SchedulerEvent.class.getName(),
                () -> new EventLogger().observe(new SchedulerEvent(SchedulerEvent.Type.STARTED, "scheduler up")));
        assertNull(system.get(0).getMDCPropertyMap().get(EventLogger.MDC_WORKFLOW_ID), "a system event belongs to no workflow");
    }

    @Test
    void aSystemEventReadsAsSystemAndItsMessage() {
        List<ILoggingEvent> lines = linesOf(SchedulerEvent.class.getName(),
                () -> new EventLogger().observe(new SchedulerEvent(SchedulerEvent.Type.STARTED, "scheduler up")));
        assertEquals(1, lines.size());
        assertEquals(Level.INFO, lines.get(0).getLevel());
        assertEquals("[SYSTEM] scheduler up", plain(lines.get(0).getFormattedMessage()), "no snapshot is a system event");
    }

    @Test
    void aJobEventCarriesTheShortenedWorkflowTheJobAndStateAndTheMessage() {
        String workflow = "analyze-documents-abc123";
        JobSnapshot snapshot = snapshot("job-7", workflow, workflow, JobType.TOOL, JobState.COMPLETED, "job-7");
        JobCompletedEvent<String> completed = new JobCompletedEvent<>(snapshot, "done", 1, List.of(), Map.of());
        List<ILoggingEvent> lines = linesOf(JOB_LOGGER, () -> new EventLogger().observe(completed));
        String line = plain(lines.get(0).getFormattedMessage());
        assertTrue(line.startsWith(" analyze-do...c123 "), "a workflow id past fifteen characters is its first ten, an ellipsis and its last four: " + line);
        assertTrue(line.contains("[job-7/COMPLETED]"), "the job id and its state in brackets: " + line);
        assertTrue(line.endsWith(completed.message()), "then the event's own message: " + line);
        JobSnapshot shortWorkflow = snapshot("job-8", "short-wf", JobState.RUNNING);
        List<ILoggingEvent> whole = linesOf(JOB_LOGGER, () -> new EventLogger().observe(new JobStartedEvent(shortWorkflow, 1)));
        assertTrue(plain(whole.get(0).getFormattedMessage()).startsWith(" short-wf "), "a short workflow id is printed whole");
    }

    @Test
    void aRawFailureGetsItsStackTraceAndAnLlmReadableOneDoesNot() {
        JobSnapshot snapshot = snapshot("job-9", "wf", JobState.FAILED);
        RuntimeException raw = new RuntimeException("disk gone");
        List<ILoggingEvent> traced = linesOf(JOB_LOGGER, () -> new EventLogger().observe(new JobFailedEvent(snapshot, raw, 1, List.of(), Map.of())));
        assertEquals(2, traced.size(), "the event line, then the trace");
        assertEquals(Level.WARN, traced.get(0).getLevel(), "a failure's line is a warning");
        assertEquals(Level.ERROR, traced.get(1).getLevel());
        assertSame(raw, ((ThrowableProxy) traced.get(1).getThrowableProxy()).getThrowable(), "the raw error is logged with its stack");
        List<ILoggingEvent> readable = linesOf(JOB_LOGGER,
                () -> new EventLogger().observe(new JobFailedEvent(snapshot, new InvalidInputException("q", "", "must not be blank"), 1, List.of(), Map.of())));
        assertEquals(1, readable.size(), "an LLM-readable failure is normal tool flow: the line, no trace");
        List<ILoggingEvent> wrapped = linesOf(JOB_LOGGER,
                () -> new EventLogger().observe(new JobFailedEvent(snapshot, new ExecutionException(raw), 1, List.of(), Map.of())));
        assertEquals(1, wrapped.size(), "a wrapped child failure printed its own trace already");
    }

    @Test
    void everyInstanceIsEqualSoABusHoldsOne() {
        assertEquals(new EventLogger(), new EventLogger());
        assertEquals(new EventLogger().hashCode(), new EventLogger().hashCode());
        LinkedQueueMessageBus bus = new LinkedQueueMessageBus();
        bus.start();
        try {
            MessageBus.Subscription first = bus.subscribe(Job.class, JobEvent.class, new EventLogger());
            MessageBus.Subscription second = bus.subscribe(Job.class, JobEvent.class, new EventLogger());
            assertNotEquals("noop", first.getId(), "the first logger is subscribed");
            assertEquals("noop", second.getId(), "a second, equal logger gets the no-op subscription: the bus holds one");
        }
        finally {
            bus.stop();
        }
    }
}
