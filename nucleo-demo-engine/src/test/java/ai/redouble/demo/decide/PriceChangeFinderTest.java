/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.decide;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.decision.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.deciding.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The decision agent on the shipped corpus, through the dispatcher, against the suite's fake
 * decision model deciding by a scripted policy: list the folder, open the two meeting
 * documents, split them, finish, and select the statements that decide a price. The trace
 * streams every turn of it: each decision with the state the model was shown and every
 * distribution it answered, each tool run with what it produced, and a last line carrying the
 * statements selected, the recorded turns and the totals.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
class PriceChangeFinderTest {
    static final Path CORPUS = Path.of("src/main/resources/corpus").toAbsolutePath();
    static final Set<String> WORTH_OPENING = Set.of("minutes-2026-05-06.md", "meeting-notes-2026-02.md");

    @BeforeAll
    static void start() {
        Assumptions.assumeTrue(Files.isDirectory(CORPUS), "the shipped corpus is at " + CORPUS);
        JobDispatcher.getInstance().start();
    }

    @BeforeEach
    void clearTheModel() {
        FakeDecisionClient.seen.clear();
        FakeDecisionClient.policy = PriceChangeFinderTest::policy;
    }

    /**
     * The policy of a model that reads the meeting documents: list first, read a document worth
     * opening while one is unread, split a document while one is unsplit, then finish; a
     * statement that decides a price from a date belongs in the answer.
     */
    static Map<String, Answer> policy(DecisionRequest request) {
        Map<String, Answer> answers = new LinkedHashMap<>();
        Choice next = (Choice) request.questions().get("next");
        if (next != null) {
            String move = null;
            String argument = null;
            if (next.options().containsKey("list_folder")) {
                move = "list_folder";
                argument = ((Choice) request.questions().get("list_folder_input")).options().keySet().iterator().next();
            }
            if (move == null && request.questions().get("read_document_input") instanceof Choice reads) {
                // a candidate reads as its ref, its type and its digest: «artifact:file~..» (file): minutes-2026-05-06.md (text, 1 KB): ...
                argument = firstWhere(reads, description -> WORTH_OPENING.stream().anyMatch(name -> description.contains("): " + name + " (")));
                move = argument != null ? "read_document" : null;
            }
            if (move == null && request.questions().get("split_statements_input") instanceof Choice splits) {
                argument = splits.options().keySet().iterator().next();
                move = "split_statements";
            }
            if (move == null) {
                assertTrue(next.options().containsKey(DecisionTurn.FINISH), "nothing left to do and finish is not offered: " + next.options());
                move = DecisionTurn.FINISH;
            }
            answers.put("next", sure(next, move));
            for (Map.Entry<String, Question> question : request.questions().entrySet()) {
                if (question.getKey().endsWith("_input")) {
                    Choice candidates = (Choice) question.getValue();
                    String chosen = question.getKey().equals(move + "_input") ? argument : candidates.options().keySet().iterator().next();
                    answers.put(question.getKey(), sure(candidates, chosen));
                }
            }
        }
        for (Map.Entry<String, Question> question : request.questions().entrySet()) {
            if (question.getValue() instanceof Noul) {
                String digest = digestOf(request, question.getKey());
                boolean decides = digest.contains("Decision:") && (digest.contains("effective") || digest.contains("from 1 June"));
                answers.put(question.getKey(), new NoulAnswer(decides ? 0.92 : 0.08));
            }
        }
        return answers;
    }

    /** The state's line about the artifact a selection question names. */
    @SuppressWarnings("unchecked")
    static String digestOf(DecisionRequest request, String selectionId) {
        String key = selectionId.substring("select_".length());
        for (String line : (List<String>) ((Map<String, Object>) request.state()).get("artifacts")) {
            if (line.contains(key)) {
                return line;
            }
        }
        throw new AssertionError("no artifact line for " + selectionId);
    }

    static String firstWhere(Choice choice, java.util.function.Predicate<String> description) {
        for (Map.Entry<String, String> option : choice.options().entrySet()) {
            if (description.test(option.getValue())) {
                return option.getKey();
            }
        }
        return null;
    }

    static ChoiceAnswer sure(Choice choice, String key) {
        assertTrue(choice.options().containsKey(key), key + " is not offered: " + choice.options().keySet());
        Map<String, Double> probabilities = new LinkedHashMap<>();
        int others = choice.options().size() - 1;
        for (String option : choice.options().keySet()) {
            probabilities.put(option, option.equals(key) ? (others == 0 ? 1.0 : 0.9) : 0.1 / others);
        }
        return new ChoiceAnswer(key, probabilities, 0.9);
    }

    @Test
    void theRunOpensTheMeetingsSplitsThemAndSelectsTheStatementsThatDecideAPrice() throws Exception {
        Identifiable workflow = Job.workflow("test-user", "decide");
        PriceChangeFinder finder = new PriceChangeFinder(workflow);
        finder.setInput(new Folder(CORPUS.toString()));
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch ended = new CountDownLatch(1);
        DecisionTrace trace = new DecisionTrace(workflow.getWorkflowId(), finder.getId(), lines::add, ended::countDown);
        MessageBus.Subscription subscription = JobDispatcher.getInstance().subscribe(trace, JobEvent.class);
        ListArtifact<Statement> answer;
        try {
            answer = JobDispatcher.getInstance().submit(finder).get();
            assertTrue(trace.awaitEnd(java.time.Duration.ofSeconds(10)), "the run's own terminal event ended the stream");
            assertTrue(ended.await(1, TimeUnit.SECONDS), "and the host was told");
        }
        finally {
            subscription.unsubscribe();
        }
        List<String> selected = answer.getIterands().stream().map(Statement::getText).toList();
        assertEquals(2, selected.size(), selected.toString());
        assertTrue(selected.contains("Decision: Meridian 3 and Kestrel 1 gravel retail and dealer prices go up by 20 percent from 1 June 2026, on the prices in force in May."), selected.toString());
        assertTrue(selected.contains("Decision: Kestrel 1 gravel retail price 2,099 euros, dealer price 1,450 euros, effective 1 April 2026."), selected.toString());
        assertEquals(List.of("list_folder", "read_document", "read_document", "split_statements", "split_statements", DecisionTurn.FINISH),
                finder.turns().stream().map(DecisionTurn::tool).toList());
        assertEquals(6, FakeDecisionClient.seen.size(), "one decision per turn");
        // the second turn's candidates for a read are the thirty files, digested as the listing saw them
        Choice reads = (Choice) FakeDecisionClient.seen.get(1).questions().get("read_document_input");
        assertEquals(30, reads.options().size());
        assertTrue(reads.options().values().stream().anyMatch(d -> d.contains("(file): keys.enc (binary, ")), reads.options().values().toString());
        // the stream: one line per event, the decisions with their whole exchange, the last line with the answer
        List<JsonNode> frames = new ArrayList<>();
        for (String line : lines) {
            frames.add(NucleoJsonSerializer.readTree(line));
        }
        JsonNode last = frames.get(frames.size() - 1);
        assertEquals("workflow_complete", last.path("type").asText(), last.toString());
        assertEquals(2, last.path("answer").size());
        assertTrue(last.path("answer").get(0).path("digest").asText().contains("Decision:"), last.path("answer").toString());
        assertEquals(6, last.path("turns").size(), "the thinker's own record rides on the last line");
        assertEquals(6, last.path("measured").path("calls").asLong());
        assertTrue(last.path("measured").path("cost").asDouble() > 0, "priced under the entry, like any model call");
        assertEquals("fake-decider", last.path("measured").path("model").asText(), "the catalog entry that decided, the name the page shows");
        List<JsonNode> decisions = frames.stream().filter(f -> f.path("kind").asText().equals("completed") && f.path("job").path("decision").asBoolean()).toList();
        assertEquals(6, decisions.size(), "every decision's completion carries its exchange");
        JsonNode second = decisions.get(1).path("decision");
        assertEquals(PriceChangeFinder.OBJECTIVE, second.path("state").path("objective").asText());
        assertEquals(32, second.path("state").path("artifacts").size(), "the folder, the listing and its thirty files, one line each, in the order they came to be");
        assertTrue(second.path("state").path("artifacts").get(2).asText().contains("FrameSerial.java"), "the files in the listing's order: " + second.path("state").path("artifacts").get(2));
        assertEquals("choice", second.path("questions").path("next").path("type").asText());
        assertEquals("read_document", second.path("answers").path("next").path("choice").asText());
        assertTrue(second.path("answers").path("next").path("probabilities").path("read_document").asDouble() > 0.5);
        assertEquals(2, decisions.get(1).path("job").path("iteration").asLong(), "stamped with its turn");
        JsonNode listing = frames.stream().filter(f -> f.path("kind").asText().equals("completed") && "list_folder".equals(f.path("job").path("tool").asText())).findFirst().orElseThrow();
        assertEquals(30, listing.path("result").path("iterands").size(), "a tool's completion carries what it produced, digested");
        assertEquals("a list of 30 file", listing.path("result").path("digest").asText(), "a list reads as its count and iterand type, its iterands under it");
        JsonNode finish = decisions.get(5).path("decision");
        long selections = 0;
        for (Iterator<String> ids = finish.path("answers").fieldNames(); ids.hasNext(); ) {
            if (ids.next().startsWith("select_")) {
                selections++;
            }
        }
        assertTrue(selections > 20, "every statement of both documents was put to the model: " + selections);
        // the thinker reports every move as progress on its own job, which the stream carries
        assertTrue(frames.stream().anyMatch(f -> f.path("kind").asText().equals("progress") && f.path("job").path("own").asBoolean()
                && f.path("message").asText().contains("turn 1: list_folder")), "a progress line per turn, naming the move");
    }

    @Test
    void aSinkThatLosesItsReaderStopsTheLinesAndNotTheRun() throws Exception {
        sinkLost(() -> new IllegalStateException("the reader left"));
        sinkLost(() -> new UncheckedIOException(new java.io.IOException("broken pipe")));
    }

    @Test
    void aFailureEndsTheStreamWithOneLastLineOnce() throws Exception {
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch ended = new CountDownLatch(1);
        DecisionTrace trace = new DecisionTrace("wf-x", "job-x", lines::add, ended::countDown);
        trace.fail(new IllegalStateException("the run never started"));
        trace.fail(new IllegalStateException("a second failure changes nothing"));
        assertTrue(trace.awaitEnd(java.time.Duration.ofSeconds(1)));
        assertEquals(1, ended.getCount() == 0 ? 1 : 0, "the host was told once");
        assertEquals(1, lines.size(), "one last line");
        JsonNode last = NucleoJsonSerializer.readTree(lines.get(0));
        assertEquals("workflow_failed", last.path("type").asText());
        assertEquals("the run never started", last.path("message").asText());
    }

    private static void sinkLost(java.util.function.Supplier<RuntimeException> leaving) throws Exception {
        Identifiable workflow = Job.workflow("test-user", "decide-unwatched");
        PriceChangeFinder finder = new PriceChangeFinder(workflow);
        finder.setInput(new Folder(CORPUS.toString()));
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        DecisionTrace trace = new DecisionTrace(workflow.getWorkflowId(), finder.getId(), line -> {
            if (lines.size() == 3) {
                throw leaving.get();
            }
            lines.add(line);
        }, () -> { });
        MessageBus.Subscription subscription = JobDispatcher.getInstance().subscribe(trace, JobEvent.class);
        try {
            ListArtifact<Statement> answer = JobDispatcher.getInstance().submit(finder).get();
            assertEquals(2, answer.getIterands().size(), "the run went to its end on the runtime");
            assertTrue(trace.awaitEnd(java.time.Duration.ofSeconds(10)), "and the stream still ended from the run's own terminal event");
        }
        finally {
            subscription.unsubscribe();
        }
        assertEquals(3, lines.size(), "nothing was written after the reader left: " + leaving.get().getClass().getSimpleName());
    }

    @Test
    void theCapabilitiesNameTheObjectiveAndThePaletteInOrderWithWhatEachToolTakesAndProduces() {
        DecideCapabilities capabilities = PriceChangeFinder.capabilities();
        assertEquals(PriceChangeFinder.OBJECTIVE, capabilities.objective());
        assertEquals("folder", capabilities.takes());
        assertEquals("statement", capabilities.answers());
        assertEquals(List.of("list_folder", "read_document", "split_statements"), capabilities.tools().stream().map(DecideCapabilities.ToolView::name).toList(),
                "the palette as the model is offered it, the key tool last");
        assertEquals(List.of("folder", "file", "document"), capabilities.tools().stream().map(DecideCapabilities.ToolView::takes).toList());
        assertEquals(List.of("list", "document", "list"), capabilities.tools().stream().map(DecideCapabilities.ToolView::produces).toList(),
                "a tool that produces many produces a list");
        assertTrue(capabilities.tools().get(1).description().contains("Office"), "what it does, from its declaration");
    }
}
