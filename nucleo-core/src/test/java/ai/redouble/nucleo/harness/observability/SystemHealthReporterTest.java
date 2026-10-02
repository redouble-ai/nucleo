/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.observability;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.harness.*;
import ch.qos.logback.classic.spi.*;
import ch.qos.logback.core.read.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static ai.redouble.nucleo.harness.observability.ObservabilityFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SystemHealthReporter}: limiter and lifecycle events pass the predicate; every fiftieth
 * event logs one snapshot with the limiter rows sorted by utilisation, each carrying the name
 * cut to 28 characters, the percentage, waiters, used and total in 1000-based short form, the
 * category's unit and the status indicator with its row tint; a token bucket's used figure is
 * the last minute's granted amount and every other category's is the event's in-use; then the
 * memory line, the running jobs counted once per job across retries, the active workflows and
 * the queue line.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class SystemHealthReporterTest {

    private static final String LOGGER = SystemHealthReporter.class.getName();
    private static final String ROW_BLOCKED = "[38;2;255;80;80m";
    private static final String ROW_THROTTLED = "[38;2;230;170;50m";
    private ListAppender<ILoggingEvent> lines;

    @BeforeEach
    void capture() {
        lines = ObservabilityFixtures.capture(LOGGER);
    }

    @AfterEach
    void release() {
        ObservabilityFixtures.release(LOGGER, lines);
    }

    private static LimiterEvent granted(String name, String category, long capacity, long inUse, long amount) {
        return limiter(snapshot("j", "wf", JobState.RUNNING), name, category, capacity, inUse, 0, LimiterEvent.Type.GRANTED_IMMEDIATE, 0, null, null, amount);
    }

    /** Feeds the reporter enough of one event to reach the fiftieth and returns the snapshot text without colors. */
    private String snapshotAfter(SystemHealthReporter reporter, JobEvent filler, int alreadyFed) {
        for (int i = alreadyFed; i < 50; i++) {
            reporter.observe(filler);
        }
        assertEquals(1, lines.list.size(), "one snapshot on the fiftieth event");
        return plain(lines.list.get(0).getFormattedMessage());
    }

    @Test
    void thePredicateKeepsLimiterAndLifecycleEventsOnly() {
        SystemHealthReporter reporter = new SystemHealthReporter();
        JobSnapshot snapshot = snapshot("j", "wf", JobState.RUNNING);
        assertTrue(reporter.getPredicate().test(granted("memory", "memory", 10, 1, 1)));
        assertTrue(reporter.getPredicate().test(new JobStartedEvent(snapshot, 1)));
        assertFalse(reporter.getPredicate().test(new JobProgressEvent<>(snapshot, "half", 50)), "progress is neither");
    }

    @Test
    void aSnapshotIsLoggedOnTheFiftiethEventAndNotBefore() {
        SystemHealthReporter reporter = new SystemHealthReporter();
        LimiterEvent event = granted("memory", "memory", 10, 1, 1);
        for (int i = 0; i < 49; i++) {
            reporter.observe(event);
        }
        assertTrue(lines.list.isEmpty(), "forty-nine events, no snapshot");
        reporter.observe(event);
        assertEquals(1, lines.list.size());
        assertTrue(lines.list.get(0).getFormattedMessage().startsWith("[System health snapshot]"));
    }

    @Test
    void limiterRowsAreSortedByUtilisationWithUnitsShortCountsAndIndicators() {
        SystemHealthReporter reporter = new SystemHealthReporter();
        reporter.observe(granted("db:main", "semaphore", 100, 30, 1));
        reporter.observe(granted("epo:search-with-a-very-long-service-name", "elastic_window", 10, 9, 1));
        reporter.observe(limiter(snapshot("j", "wf", JobState.RUNNING), "heap", "memory", 34_400_000_000L, 7_900_000_000L, 3,
                LimiterEvent.Type.HELD, 0, null, "pace:1500ms", 1));
        String text = snapshotAfter(reporter, granted("db:main", "semaphore", 100, 30, 1), 3);
        int epo = text.indexOf("epo:search-with-a-very-lo...");
        int db = text.indexOf("db:main");
        int heap = text.indexOf("heap");
        assertTrue(epo > 0 && db > 0 && heap > 0, text);
        assertTrue(epo < db && db < heap, "rows sort by utilisation descending: 90%, then 30%, then 23%: " + text);
        assertTrue(text.contains("90.0%"), text);
        assertTrue(text.contains("used:   7.9G  total: 34.4G  B"), "1000-based short counts and the memory unit: " + text);
        assertTrue(text.contains("total: 100    slot"), "a semaphore counts slots: " + text);
        assertTrue(text.contains("total: 10     req"), "an elastic window counts requests: " + text);
        assertTrue(text.contains("waiters=3"), text);
        assertTrue(text.contains("pace:1500ms"), "the indicator ends the row: " + text);
    }

    @Test
    void abnormalRowsAreTintedRedForBlockedAndYellowForThrottlePaceOrProbing() {
        SystemHealthReporter reporter = new SystemHealthReporter();
        JobSnapshot snapshot = snapshot("j", "wf", JobState.RUNNING);
        reporter.observe(limiter(snapshot, "circuit", "elastic_window", 10, 0, 0, LimiterEvent.Type.REJECTED, 0, "circuit_blocked", "blocked", 1));
        reporter.observe(limiter(snapshot, "paced", "memory", 10, 1, 1, LimiterEvent.Type.HELD, 0, null, "pace:1500ms", 1));
        reporter.observe(limiter(snapshot, "probe", "elastic_window", 10, 1, 0, LimiterEvent.Type.GRANTED_IMMEDIATE, 0, null, "probing", 1));
        reporter.observe(limiter(snapshot, "slow", "token_bucket", 10, 1, 0, LimiterEvent.Type.GRANTED_IMMEDIATE, 0, null, "throttle:2.0x", 1));
        reporter.observe(granted("calm", "semaphore", 10, 1, 1));
        for (int i = 5; i < 50; i++) {
            reporter.observe(granted("calm", "semaphore", 10, 1, 1));
        }
        String colored = lines.list.get(0).getFormattedMessage();
        for (String row : colored.split("\n")) {
            if (row.contains("circuit")) assertTrue(row.startsWith(ROW_BLOCKED), "blocked is red: " + row);
            if (row.contains("paced")) assertTrue(row.startsWith(ROW_THROTTLED), "pace is yellow: " + row);
            if (row.contains("probe")) assertTrue(row.startsWith(ROW_THROTTLED), "probing is yellow: " + row);
            if (row.contains("slow")) assertTrue(row.startsWith(ROW_THROTTLED), "throttle is yellow: " + row);
            if (row.contains("calm")) assertFalse(row.startsWith(ROW_THROTTLED) || row.startsWith(ROW_BLOCKED), "a nominal row is untinted: " + row);
        }
    }

    @Test
    void aTokenBucketsUsedFigureIsTheMinutesGrantedAmountAndOthersAreTheEventsInUse() {
        SystemHealthReporter reporter = new SystemHealthReporter();
        // three grants of 400 tokens on a 2,000-per-minute bucket whose in-use snapshot reads 5
        reporter.observe(granted("model-a", "token_bucket", 2_000, 5, 400));
        reporter.observe(granted("model-a", "token_bucket", 2_000, 5, 400));
        reporter.observe(granted("model-a", "token_bucket", 2_000, 5, 400));
        reporter.observe(limiter(snapshot("j", "wf", JobState.RUNNING), "model-a", "token_bucket", 2_000, 5, 0, LimiterEvent.Type.RELEASED, 0, null, null, 400));
        String text = snapshotAfter(reporter, granted("db:x", "semaphore", 100, 40, 1), 4);
        assertTrue(text.contains("model-a") && text.contains("60.0%") && text.contains("used:   1.2K  total: 2.0K   tok"),
                "1,200 granted this minute against 2,000 per minute, not the in-use of 5: " + text);
        assertTrue(text.contains("db:x") && text.contains("40.0%") && text.contains("used:     40"), "a semaphore shows its in-use: " + text);
    }

    @Test
    void runningJobsCountOncePerJobAcrossRetriesAndLeaveOnTheirTerminalEvent() {
        SystemHealthReporter reporter = new SystemHealthReporter();
        JobSnapshot tool = snapshot("t-1", null, "wf-1", JobType.TOOL, JobState.RUNNING, "tool");
        JobSnapshot thinker = snapshot("k-1", null, "wf-2", JobType.THINKER, JobState.RUNNING, "thinker");
        JobSnapshot done = snapshot("t-2", null, "wf-1", JobType.TOOL, JobState.COMPLETED, "tool");
        reporter.observe(new JobStartedEvent(tool, 1));
        reporter.observe(new JobStartedEvent(tool, 2));
        reporter.observe(new JobStartedEvent(tool, 3));
        reporter.observe(new JobStartedEvent(thinker, 1));
        reporter.observe(new JobStartedEvent(done, 1));
        reporter.observe(new JobCompletedEvent<>(done, "ok", 1, List.of(), Map.of()));
        String text = snapshotAfter(reporter, new JobProgressEvent<>(tool, "filler", 1), 6);
        assertTrue(text.contains("Jobs running: 2 total"), "three starts of one job count once; the finished job left: " + text);
        assertTrue(text.contains("TOOL          1") && text.contains("THINKER       1"), "by type: " + text);
        assertTrue(text.contains("Workflows active: 2"), text);
        assertTrue(text.contains("Memory: "), "the memory gate's own line: " + text);
        assertTrue(text.contains("Queue: ") && text.contains("parked in admission"), "the dispatcher's queues: " + text);
        assertTrue(text.contains("Limiters: (none active)"), "no limiter has spoken: " + text);
    }
}
