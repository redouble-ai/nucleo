/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.observability;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.llm.*;
import ch.qos.logback.classic.spi.*;
import ch.qos.logback.core.read.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.charset.*;
import java.time.*;
import java.util.*;

import static ai.redouble.nucleo.harness.observability.ObservabilityFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link StreamingLogObserver}: only progress events are read; a stream chunk prints inline,
 * or boxed for reasoning and tool announcements, or check-marked for a completed tool, and the
 * last chunk logs {@code STREAMING COMPLETE}; a progress percentage logs {@code Progress: N%}
 * with a non-chunk payload appended, a plain string without one logs as it is; each output
 * kind has its flag; equality is the workflow, the two flags and whether a timeout exists;
 * and the workflow filter and inactivity staleness of the base class hold through it.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class StreamingLogObserverTest {

    private static final String LOGGER = StreamingLogObserver.class.getName();
    private ListAppender<ILoggingEvent> lines;
    private PrintStream originalOut;
    private ByteArrayOutputStream out;

    @BeforeEach
    void capture() {
        lines = ObservabilityFixtures.capture(LOGGER);
        originalOut = System.out;
        out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void release() {
        System.setOut(originalOut);
        ObservabilityFixtures.release(LOGGER, lines);
    }

    private String printed() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private static JobProgressEvent<Object> chunk(String content, boolean last) {
        return new JobProgressEvent<>(snapshot("j", "wf", JobState.RUNNING), new StreamChunk(content, 1, last));
    }

    @Test
    void ordinaryContentPrintsInlineAndTheLastChunkClosesTheStream() {
        StreamingLogObserver<JobEvent> observer = new StreamingLogObserver<>("wf");
        observer.observe(chunk("Hello, ", false));
        observer.observe(chunk("world", false));
        assertEquals("Hello, world", printed(), "content streams inline with no line breaks of its own");
        observer.observe(chunk("", true));
        assertTrue(printed().endsWith(System.lineSeparator()), "the last chunk ends the line");
        assertEquals(1, lines.list.size());
        assertTrue(lines.list.get(0).getFormattedMessage().contains("STREAMING COMPLETE"), "and says the stream is complete");
    }

    @Test
    void reasoningAndToolAnnouncementsAreBoxedAndACompletedToolIsCheckMarked() {
        StreamingLogObserver<JobEvent> observer = new StreamingLogObserver<>("wf");
        observer.observe(chunk("[REASONING] I should search first", false));
        String reasoning = printed();
        assertTrue(reasoning.contains("╔") && reasoning.contains("╝"), "reasoning is boxed in double lines: " + reasoning);
        assertTrue(reasoning.contains("🧠 THINKER [REASONING] I should search first"), reasoning);
        out.reset();
        observer.observe(chunk("[TOOLS TO EXECUTE] search", false));
        String tools = printed();
        assertTrue(tools.contains("┌") && tools.contains("┘"), "a tool announcement is boxed in single lines: " + tools);
        assertTrue(tools.contains("🔧 [TOOLS TO EXECUTE] search"), tools);
        out.reset();
        observer.observe(chunk("[TOOL COMPLETED] search", false));
        assertEquals("    ✅ [TOOL COMPLETED] search" + System.lineSeparator(), printed(), "a completed tool is one check-marked line");
    }

    @Test
    void aBoxWrapsLongContentAtWordBoundaries() {
        new StreamingLogObserver<JobEvent>("wf").observe(chunk("[REASONING] " + "word ".repeat(40).trim(), false));
        String[] rows = printed().split(System.lineSeparator());
        long boxed = Arrays.stream(rows).filter(r -> r.startsWith("║")).count();
        assertTrue(boxed > 1, "content past the box width continues on further rows: " + printed());
        for (String row : rows) {
            if (row.startsWith("║")) {
                assertTrue(row.endsWith(" ║"), "every row closes the box: " + row);
                assertFalse(row.contains("wor ") , "rows break at spaces, never inside a word: " + row);
            }
        }
    }

    @Test
    void progressAndStatusLinesAreLoggedAndChunksAreNot() {
        StreamingLogObserver<JobEvent> observer = new StreamingLogObserver<>("wf");
        JobSnapshot snapshot = snapshot("j", "wf", JobState.RUNNING);
        observer.observe(new JobProgressEvent<>(snapshot, "indexing", 40));
        assertEquals("Progress: 40% - indexing", lines.list.get(0).getFormattedMessage(), "a percentage with its payload");
        observer.observe(new JobProgressEvent<>(snapshot, "warming up"));
        assertEquals("warming up", lines.list.get(1).getFormattedMessage(), "a plain status line as it is");
        observer.observe(new JobProgressEvent<>(snapshot, new StreamChunk("x", 1, false), 70));
        assertEquals("Progress: 70%", lines.list.get(2).getFormattedMessage(), "a chunk payload is not appended to the progress line");
        observer.observe(new JobStartedEvent(snapshot, 1));
        assertEquals(3, lines.list.size(), "an event that is not progress is ignored");
    }

    @Test
    void theTwoFlagsSilenceTheirOutput() {
        JobSnapshot snapshot = snapshot("j", "wf", JobState.RUNNING);
        // the console appender of the test logging config writes log lines to the same captured
        // stream, so the chunk assertions look for the chunk text rather than the whole stream
        StreamingLogObserver<JobEvent> noChunks = new StreamingLogObserver<>("wf", null, false, true);
        noChunks.observe(chunk("silent", false));
        assertFalse(printed().contains("silent"), "chunks off: the chunk is not printed");
        noChunks.observe(new JobProgressEvent<>(snapshot, "still logged", 10));
        assertEquals(1, lines.list.size(), "status still logged");
        StreamingLogObserver<JobEvent> noStatus = new StreamingLogObserver<>("wf", null, true, false);
        noStatus.observe(new JobProgressEvent<>(snapshot, "not logged", 20));
        noStatus.observe(new JobProgressEvent<>(snapshot, "not either"));
        assertEquals(1, lines.list.size(), "status off: nothing more logged");
        out.reset();
        noStatus.observe(chunk("printed", false));
        assertEquals("printed", printed(), "chunks still print");
    }

    @Test
    void equalityIsTheWorkflowTheFlagsAndWhetherATimeoutExists() {
        assertEquals(new StreamingLogObserver<>("wf"), new StreamingLogObserver<>("wf", null, true, true));
        assertEquals(new StreamingLogObserver<>("wf").hashCode(), new StreamingLogObserver<>("wf", null, true, true).hashCode());
        assertNotEquals(new StreamingLogObserver<>("wf"), new StreamingLogObserver<>("other"), "another workflow is another observer");
        assertNotEquals(new StreamingLogObserver<>("wf"), new StreamingLogObserver<>("wf", null, false, true), "another configuration is another observer");
        assertNotEquals(new StreamingLogObserver<>("wf"), new StreamingLogObserver<>("wf", Duration.ofMinutes(1)), "a timeout makes a different observer");
        assertEquals(new StreamingLogObserver<>("wf", Duration.ofMinutes(1)), new StreamingLogObserver<>("wf", Duration.ofHours(2)),
                "the timeout's length does not, only its presence");
    }

    @Test
    void theWorkflowFilterAndTheInactivityStalenessHoldThroughTheBase() throws InterruptedException {
        StreamingLogObserver<JobEvent> observer = new StreamingLogObserver<>("wf", Duration.ofMillis(20));
        assertTrue(observer.getPredicate().test(new JobStartedEvent(snapshot("j", "wf", JobState.RUNNING), 1)), "its own workflow");
        assertFalse(observer.getPredicate().test(new JobStartedEvent(snapshot("j", "other", JobState.RUNNING), 1)), "another workflow is filtered out");
        assertFalse(observer.getPredicate().test(new SchedulerEvent(SchedulerEvent.Type.STARTED, "no snapshot")), "an event with no snapshot is filtered out");
        assertFalse(observer.isStale(), "fresh");
        Thread.sleep(40);
        assertTrue(observer.isStale(), "past the inactivity timeout with no event, stale");
        assertFalse(new StreamingLogObserver<>("wf").isStale(), "no timeout, never stale");
    }
}
