/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.observability;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.harness.*;
import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.*;
import ch.qos.logback.core.read.*;

import java.time.*;
import java.util.*;

/**
 * Hand-built snapshots, limiter events and log capture for the observer tests: every event
 * an observer here reads can be built without a dispatcher, and every line an observer logs
 * can be read back off a Logback list appender on its logger.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
final class ObservabilityFixtures {

    private ObservabilityFixtures() {
    }

    static JobSnapshot snapshot(String jobId, String parentJobId, String workflowId, JobType type, JobState state, String displayName) {
        return snapshotOf(Job.class, jobId, parentJobId, workflowId, type, state, displayName);
    }

    /** A snapshot of a named job class, for the observers that read the class off the snapshot. */
    static JobSnapshot snapshotOf(Class<? extends Job> jobClass, String jobId, String parentJobId, String workflowId, JobType type, JobState state, String displayName) {
        Instant now = Instant.now();
        return new JobSnapshot(jobId, parentJobId, workflowId, "tester", jobClass, type, displayName, null, null,
                state, 1, now.minusMillis(100), now.minusMillis(50), state == JobState.RUNNING ? null : now, List.of(), Map.of());
    }

    static JobSnapshot snapshot(String jobId, String workflowId, JobState state) {
        return snapshot(jobId, workflowId, workflowId, JobType.TOOL, state, jobId);
    }

    static LimiterEvent limiter(JobSnapshot snapshot, String name, String category, long capacity, long inUse, int waiters,
                                LimiterEvent.Type type, long waitNanos, String rejectReason, String indicator, long amount) {
        return new LimiterEvent(snapshot, Instant.now(), name, category, capacity, inUse, waiters, type, waitNanos, rejectReason, indicator, amount);
    }

    /** A list appender on the named logger, started; the caller detaches it in a finally. */
    static ListAppender<ILoggingEvent> capture(String loggerName) {
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(loggerName);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    static void release(String loggerName, ListAppender<ILoggingEvent> appender) {
        ((Logger) org.slf4j.LoggerFactory.getLogger(loggerName)).detachAppender(appender);
    }

    /** The message with every ANSI escape removed, so an assertion reads what a person reads. */
    static String plain(String colored) {
        return colored.replaceAll("\\[[0-9;]*m", "");
    }
}
