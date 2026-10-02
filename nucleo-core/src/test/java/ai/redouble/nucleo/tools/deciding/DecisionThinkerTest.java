/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.deciding;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.decision.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.retry.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.observability.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A decision thinker through the dispatcher against the suite's fake decision model, deciding
 * by a policy the test installs: the loop offers only legal moves, runs the chosen tool on
 * the chosen artifact, never offers a pair twice, feeds a tool's readable failure back as a
 * fact of the run, finishes on the model's word with the artifacts it selects, and ends on
 * the turn budget with what the model would select then. What the run leaves behind is every
 * turn's distributions, on the thinker and on the job's record.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
class DecisionThinkerTest {

    @BeforeAll
    static void startDispatcher() {
        JobDispatcher.getInstance().start();
    }

    @BeforeEach
    void clearTheModel() {
        FakeDecisionClient.seen.clear();
        FakeDecisionClient.policy = null;
    }

    @TypeAlias("note")
    public static class Note extends AbstractArtifact {
        @LLMDescription("The note's text")
        private String text;

        public Note() {}

        public Note(String text) {
            this.text = text;
        }

        public String getText() {return text;}

        public void setText(String text) {this.text = text;}
    }

    @TypeAlias("verdict")
    public static class Verdict extends AbstractArtifact {
        @LLMDescription("The note judged")
        private String about;
        @LLMDescription("Whether the note is worth keeping")
        private Boolean keep;

        public Verdict() {}

        public Verdict(String about, boolean keep) {
            this.about = about;
            this.keep = keep;
        }

        public String getAbout() {return about;}

        public void setAbout(String about) {this.about = about;}

        public Boolean getKeep() {return keep;}

        public void setKeep(Boolean keep) {this.keep = keep;}
    }

    /** Splits a note on its semicolons into a list of notes. */
    @ToolName("split_note")
    @ToolDescription(value = "Splits a note into its parts", readOnly = true)
    public static class SplitNote extends DecisionTool<Note, ListArtifact<Note>> {
        public SplitNote(Identifiable parent) {
            super(parent);
        }

        @Override
        public ListArtifact<Note> execute(JobResources resources, JobContext<ListArtifact<Note>> context) {
            ListArtifact<Note> parts = new ListArtifact<>();
            List<Note> notes = new ArrayList<>();
            for (String part : input.getText().split(";")) {
                notes.add(new Note(part.trim()));
            }
            parts.setIterands(notes);
            parts.setIterandTypeAlias("note");
            return parts;
        }
    }

    /** Judges a note: kept when it mentions alpha. The verdicts on a note, a list of one; the key tool of the triage. */
    @ToolName("judge_note")
    @ToolDescription(value = "Judges whether a note is worth keeping", readOnly = true)
    public static class JudgeNote extends DecisionTool<Note, ListArtifact<Verdict>> {
        public JudgeNote(Identifiable parent) {
            super(parent);
        }

        @Override
        public ListArtifact<Verdict> execute(JobResources resources, JobContext<ListArtifact<Verdict>> context) {
            ListArtifact<Verdict> verdicts = new ListArtifact<>();
            verdicts.setIterands(List.of(new Verdict(input.getText(), input.getText().contains("alpha"))));
            verdicts.setIterandTypeAlias("verdict");
            return verdicts;
        }
    }

    /** Refuses every note with a failure the model can read. */
    @ToolName("picky_judge")
    @ToolDescription(value = "Judges a note when it is short enough", readOnly = true)
    public static class PickyJudge extends DecisionTool<Note, ListArtifact<Verdict>> {
        public PickyJudge(Identifiable parent) {
            super(parent);
        }

        @Override
        public ListArtifact<Verdict> execute(JobResources resources, JobContext<ListArtifact<Verdict>> context) throws LLMReadableCheckedException {
            throw new InvalidInputException("note", input.getText(), "the picky judge reads nothing longer than three characters");
        }
    }

    /** Fails on every note with a failure no model can read: a bug, never a verdict. */
    @ToolName("broken_judge")
    @ToolDescription(value = "Judges a note, when its database is up", readOnly = true)
    public static class BrokenJudge extends DecisionTool<Note, ListArtifact<Verdict>> {
        public BrokenJudge(Identifiable parent) {
            super(parent);
        }

        @Override
        public ListArtifact<Verdict> execute(JobResources resources, JobContext<ListArtifact<Verdict>> context) {
            throw new IllegalStateException("the judge's database is gone");
        }
    }

    /** A triage over a key tool and the rest of its palette; the palette is legal because it compiled. */
    static class Triage extends DecisionThinker<Note, Verdict> {
        Triage(Identifiable parent, Class<? extends DecisionTool<?, ListArtifact<Verdict>>> keyTool, List<Class<? extends DecisionTool<?, ?>>> palette) {
            super(parent, Verdict.class, keyTool, palette, "Judge every part of the note and keep the verdicts on the parts worth keeping");
        }
    }

    /**
     * The policy of a model that splits what can be split, judges the parts, and finishes
     * when nothing is left to judge; a verdict about alpha belongs in the answer, one about
     * beta does not.
     */
    static Map<String, Answer> splitThenJudge(DecisionRequest request) {
        Map<String, Answer> answers = new LinkedHashMap<>();
        Choice next = (Choice) request.questions().get("next");
        String move = null;
        String argument = null;
        if (next != null) {
            Choice splits = (Choice) request.questions().get("split_note_input");
            Choice judges = (Choice) request.questions().get("judge_note_input");
            if (splits != null) {
                argument = firstWhere(splits, description -> description.contains(";"));
                move = argument != null ? "split_note" : null;
            }
            if (move == null && judges != null) {
                argument = firstWhere(judges, description -> !description.contains(";"));
                move = argument != null ? "judge_note" : null;
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
            if (question.getValue() instanceof Noul noul) {
                answers.put(question.getKey(), new NoulAnswer(noul.instructions().contains("verdict") && selectedByDigest(request, question.getKey()) ? 0.9 : 0.1));
            }
        }
        return answers;
    }

    /** Whether the artifact a selection question names is digested as a verdict about alpha. */
    @SuppressWarnings("unchecked")
    static boolean selectedByDigest(DecisionRequest request, String selectionId) {
        String key = selectionId.substring("select_".length());
        List<String> artifacts = (List<String>) ((Map<String, Object>) request.state()).get("artifacts");
        for (String digest : artifacts) {
            if (digest.contains(key) && digest.contains("alpha")) {
                return true;
            }
        }
        return false;
    }

    static String firstWhere(Choice choice, Predicate<String> description) {
        for (Map.Entry<String, String> option : choice.options().entrySet()) {
            if (description.test(option.getValue())) {
                return option.getKey();
            }
        }
        return null;
    }

    /** A distribution that puts nearly everything on one option. */
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
    void theRunSplitsJudgesAndFinishesWithTheSelectedVerdicts() throws Exception {
        FakeDecisionClient.policy = DecisionThinkerTest::splitThenJudge;
        Identifiable workflow = Job.workflow("test-user", "decision-thinker");
        // the bus delivers to each subscriber on its own queue, after the job's own completion:
        // what a subscriber is owed is the event, not its arrival before get() returns
        Map<String, Object> recorded = new ConcurrentHashMap<>();
        CountDownLatch completed = new CountDownLatch(1);
        MessageBus.Subscription subscription = JobDispatcher.getInstance().subscribe(new JobObserver<JobCompletedEvent>() {
            @Override
            public java.util.function.Predicate<JobCompletedEvent> getPredicate() {
                return event -> workflow.getWorkflowId().equals(event.snapshot().getWorkflowId())
                        && event.snapshot().getMetadata().containsKey(DecisionThinker.OBS_TURNS);
            }

            @Override
            public void observe(JobCompletedEvent event) {
                recorded.putAll(event.snapshot().getMetadata());
                completed.countDown();
            }
        }, JobCompletedEvent.class);
        // the record is published after every turn: the progress the second turn reports already carries the first
        Map<String, Object> midRun = new ConcurrentHashMap<>();
        CountDownLatch secondTurn = new CountDownLatch(1);
        MessageBus.Subscription progress = JobDispatcher.getInstance().subscribe(new JobObserver<JobEvent>() {
            @Override
            public java.util.function.Predicate<JobEvent> getPredicate() {
                return event -> event instanceof JobProgressEvent<?> step && workflow.getWorkflowId().equals(step.snapshot().getWorkflowId())
                        && step.getHumanMessage() != null && step.getHumanMessage().contains("turn 2:");
            }

            @Override
            public void observe(JobEvent event) {
                midRun.putAll(event.snapshot().getMetadata());
                secondTurn.countDown();
            }
        }, JobEvent.class);
        try {
            Triage triage = new Triage(workflow, JudgeNote.class, List.of(SplitNote.class));
            triage.setInput(new Note("alpha is due; beta can wait"));
            ListArtifact<Verdict> answer = JobDispatcher.getInstance().submit(triage).get();
            assertEquals(1, answer.getIterands().size(), "the alpha verdict alone was selected");
            assertEquals("alpha is due", answer.getIterands().get(0).getAbout());
            assertEquals("verdict", answer.getIterandTypeAlias());
            assertNotNull(answer.getArtifactRef(), "the answer is an artifact of the run");
            List<DecisionTurn> turns = triage.turns();
            assertEquals(List.of("split_note", "judge_note", "judge_note", DecisionTurn.FINISH), turns.stream().map(DecisionTurn::tool).toList());
            assertTrue(turns.get(3).finished());
            assertEquals(4, FakeDecisionClient.seen.size(), "one decision per turn");
            // turn one: no verdict exists yet, so finishing is not on offer
            Choice first = (Choice) FakeDecisionClient.seen.get(0).questions().get("next");
            assertFalse(first.options().containsKey(DecisionTurn.FINISH));
            assertEquals(Set.of("split_note", "judge_note"), first.options().keySet());
            // turn two: the split parts are candidates by their own type; the list itself is not a note
            Choice judgeCandidates = (Choice) FakeDecisionClient.seen.get(1).questions().get("judge_note_input");
            assertEquals(3, judgeCandidates.options().size(), "the whole note and its two parts: " + judgeCandidates.options());
            List<String> offered = new ArrayList<>(judgeCandidates.options().values());
            assertTrue(offered.get(0).contains("alpha is due; beta") && offered.get(1).contains("alpha is due\"") && offered.get(2).contains("beta can wait\""),
                    "in the order they came to be, the whole note first and the parts in the list's order: " + offered);
            Choice splitCandidates = (Choice) FakeDecisionClient.seen.get(1).questions().get("split_note_input");
            assertEquals(2, splitCandidates.options().size(), "the whole note was split already; the parts remain: " + splitCandidates.options());
            // the last turn: the whole note is the one judge candidate left, and finish is offered beside it
            Choice last = (Choice) FakeDecisionClient.seen.get(3).questions().get("next");
            assertTrue(last.options().containsKey(DecisionTurn.FINISH));
            assertEquals(2, turns.get(3).selection().size(), "both verdicts were put to the model");
            assertEquals(1, turns.get(3).selection().values().stream().filter(p -> p >= DecisionThinker.DEFAULT_SELECTION_THRESHOLD).count());
            // every turn's distributions are on the thinker and on the job's record
            assertNotNull(turns.get(0).next());
            assertEquals(2, turns.get(0).next().probabilities().size());
            assertNotNull(turns.get(1).argument());
            assertEquals(turns.get(1).artifact(), turns.get(1).argument().choice());
            assertNotNull(turns.get(1).result());
            assertTrue(completed.await(10, TimeUnit.SECONDS), "the completion event reaches its subscriber");
            String record = String.valueOf(recorded.get(DecisionThinker.OBS_TURNS));
            assertTrue(record.contains("split_note") && record.contains("\"finish\""), record);
            assertTrue(secondTurn.await(10, TimeUnit.SECONDS), "the second turn's progress event reaches its subscriber");
            String partial = String.valueOf(midRun.get(DecisionThinker.OBS_TURNS));
            assertTrue(partial.contains("\"turn\":1") && !partial.contains("\"finish\""), "the first turn on the record before the run ends: " + partial);
            // the first state says nothing has been done yet
            @SuppressWarnings("unchecked")
            List<String> done = (List<String>) ((Map<String, Object>) FakeDecisionClient.seen.get(0).state()).get("done");
            assertEquals(List.of("nothing yet"), done);
            assertEquals(32, DecisionThinker.DEFAULT_MAX_TURNS, "the turn budget a run starts with");
            assertEquals(0.5, DecisionThinker.DEFAULT_SELECTION_THRESHOLD, 1e-9);
        }
        finally {
            subscription.unsubscribe();
            progress.unsubscribe();
        }
    }

    @Test
    void aTurnKeepsItsSelectionAsAnUnmodifiableMapNeverNull() {
        DecisionTurn bare = new DecisionTurn(1, null, DecisionTurn.FINISH, null, null, null, null, null);
        assertNotNull(bare.selection());
        assertTrue(bare.selection().isEmpty());
        assertTrue(bare.finished());
        LinkedHashMap<String, Double> asked = new LinkedHashMap<>();
        asked.put("«artifact:verdict~1»", 0.9);
        asked.put("«artifact:verdict~2»", 0.1);
        DecisionTurn turn = new DecisionTurn(2, null, "judge_note", null, "«artifact:note~1»", "«artifact:list~1»", null, asked);
        assertEquals(List.of("«artifact:verdict~1»", "«artifact:verdict~2»"), new ArrayList<>(turn.selection().keySet()), "in the order asked");
        assertThrows(UnsupportedOperationException.class, () -> turn.selection().put("x", 1.0));
        assertFalse(turn.finished());
    }

    @Test
    void aToolsReadableFailureIsAFactOfTheRunAndThePairIsNotOfferedAgain() throws Exception {
        // the policy: picky first while it is offered, then judge, then finish
        FakeDecisionClient.policy = request -> {
            Map<String, Answer> answers = new LinkedHashMap<>();
            Choice next = (Choice) request.questions().get("next");
            if (next != null) {
                String move = next.options().containsKey("picky_judge") ? "picky_judge"
                        : next.options().containsKey("judge_note") ? "judge_note" : DecisionTurn.FINISH;
                answers.put("next", sure(next, move));
                for (Map.Entry<String, Question> question : request.questions().entrySet()) {
                    if (question.getValue() instanceof Choice candidates && question.getKey().endsWith("_input")) {
                        answers.put(question.getKey(), sure(candidates, candidates.options().keySet().iterator().next()));
                    }
                }
            }
            for (Map.Entry<String, Question> question : request.questions().entrySet()) {
                if (question.getValue() instanceof Noul) {
                    answers.put(question.getKey(), new NoulAnswer(0.8));
                }
            }
            return answers;
        };
        Identifiable workflow = Job.workflow("test-user", "decision-thinker-failure");
        Triage triage = new Triage(workflow, JudgeNote.class, List.of(PickyJudge.class));
        triage.setInput(new Note("alpha"));
        ListArtifact<Verdict> answer = JobDispatcher.getInstance().submit(triage).get();
        assertEquals(1, answer.getIterands().size());
        List<DecisionTurn> turns = triage.turns();
        assertEquals(List.of("picky_judge", "judge_note", DecisionTurn.FINISH), turns.stream().map(DecisionTurn::tool).toList());
        assertNotNull(turns.get(0).failure(), "the failure is recorded in the model's words");
        assertTrue(turns.get(0).failure().contains("three characters"), turns.get(0).failure());
        assertNull(turns.get(0).result());
        Choice second = (Choice) FakeDecisionClient.seen.get(1).questions().get("next");
        assertFalse(second.options().containsKey("picky_judge"), "the failed pair is spent: " + second.options());
        @SuppressWarnings("unchecked")
        List<String> done = (List<String>) ((Map<String, Object>) FakeDecisionClient.seen.get(1).state()).get("done");
        assertTrue(done.get(0).contains("failed"), "and the model reads what happened: " + done);
    }

    @Test
    void theTurnBudgetEndsTheRunWithWhatTheModelSelectsThen() throws Exception {
        FakeDecisionClient.policy = DecisionThinkerTest::splitThenJudge;
        Identifiable workflow = Job.workflow("test-user", "decision-thinker-budget");
        Triage triage = new Triage(workflow, JudgeNote.class, List.of(SplitNote.class));
        triage.setMaxTurns(2);
        triage.setInput(new Note("alpha is due; beta can wait"));
        ListArtifact<Verdict> answer = JobDispatcher.getInstance().submit(triage).get();
        List<DecisionTurn> turns = triage.turns();
        assertEquals(List.of("split_note", "judge_note", DecisionTurn.FINISH), turns.stream().map(DecisionTurn::tool).toList());
        assertEquals(3, turns.get(2).turn(), "the budget's own turn is numbered after the last one played");
        assertNull(turns.get(2).next(), "no move was offered on the budget's turn");
        assertEquals(1, turns.get(2).selection().size(), "the one verdict produced was put to the model");
        assertEquals(1, answer.getIterands().size());
        assertEquals("alpha is due", answer.getIterands().get(0).getAbout());
        DecisionRequest last = FakeDecisionClient.seen.get(2);
        assertNull(last.questions().get("next"), "the selection alone is asked");
        assertEquals(1, last.questions().size());
    }

    @Test
    void aRunWithNothingToDoAndNothingProducedAnswersEmpty() throws Exception {
        // a palette whose only tool refuses: after the one legal move fails, nothing is left
        FakeDecisionClient.policy = request -> {
            Map<String, Answer> answers = new LinkedHashMap<>();
            for (Map.Entry<String, Question> question : request.questions().entrySet()) {
                if (question.getValue() instanceof Choice choice) {
                    answers.put(question.getKey(), sure(choice, choice.options().keySet().iterator().next()));
                }
                else {
                    answers.put(question.getKey(), new NoulAnswer(0.5));
                }
            }
            return answers;
        };
        Identifiable workflow = Job.workflow("test-user", "decision-thinker-empty");
        // the bus delivers to each subscriber on its own queue, after the job's own completion
        Map<String, Object> recorded = new ConcurrentHashMap<>();
        CountDownLatch completed = new CountDownLatch(1);
        MessageBus.Subscription subscription = JobDispatcher.getInstance().subscribe(new JobObserver<JobCompletedEvent>() {
            @Override
            public java.util.function.Predicate<JobCompletedEvent> getPredicate() {
                return event -> workflow.getWorkflowId().equals(event.snapshot().getWorkflowId())
                        && event.snapshot().getMetadata().containsKey(DecisionThinker.OBS_TURNS);
            }

            @Override
            public void observe(JobCompletedEvent event) {
                recorded.putAll(event.snapshot().getMetadata());
                completed.countDown();
            }
        }, JobCompletedEvent.class);
        try {
            Triage triage = new Triage(workflow, PickyJudge.class, List.of());
            triage.setInput(new Note("alpha"));
            ListArtifact<Verdict> answer = JobDispatcher.getInstance().submit(triage).get();
            assertTrue(answer.getIterands().isEmpty());
            assertEquals(List.of("picky_judge", DecisionTurn.FINISH), triage.turns().stream().map(DecisionTurn::tool).toList());
            assertEquals(1, FakeDecisionClient.seen.size(), "no decision is asked when there is nothing to decide");
            assertTrue(completed.await(10, TimeUnit.SECONDS), "the completion event reaches its subscriber");
            String record = String.valueOf(recorded.get(DecisionThinker.OBS_TURNS));
            assertTrue(record.contains("\"finish\""), "the record is published on this path too, its terminal turn included: " + record);
        }
        finally {
            subscription.unsubscribe();
        }
    }

    @Test
    void theThinkerHandsItsRetryBudgetToEveryDecisionItAsks() {
        // the endpoint fails once with a transient signal; a thinker whose budget is zero
        // hands that zero to the call, so the one failure is the run's failure at once,
        // never waited out on the dispatcher's default budget
        FakeDecisionClient.policy = request -> {
            throw new TransientErrorRetryException("the endpoint failed", "systemone", "scripted 503", 503, 1, null);
        };
        Identifiable workflow = Job.workflow("test-user", "decision-thinker-retry-budget");
        Triage triage = new Triage(workflow, JudgeNote.class, List.of(SplitNote.class));
        triage.setUpstreamRetries(0);
        triage.setInput(new Note("alpha is due; beta can wait"));
        ExecutionException refused = assertThrows(ExecutionException.class,
                () -> JobDispatcher.getInstance().submit(triage).get(),
                "the thinker's zero budget reaches the decision call");
        assertTrue(String.valueOf(refused.getCause().getMessage()).contains("retry budget (0)"),
                "the failure names the thinker's own budget: " + refused.getCause().getMessage());
    }

    @Test
    void theKeyToolIsFoldedIntoThePaletteOnceAndItsOutputIsWhatTheAnswerSelectsFrom() throws Exception {
        // the contract itself is the compiler's: a tool that is not a DecisionTool, or a key
        // tool that does not produce a list of the answer type, does not compile into a palette
        Identifiable workflow = Job.workflow("test-user", "decision-thinker-palette");
        assertEquals(List.of("split_note", "judge_note"), new Triage(workflow, JudgeNote.class, List.of(SplitNote.class)).paletteNames(),
                "the key tool follows the rest of the palette");
        assertEquals(List.of("split_note", "judge_note"), new Triage(workflow, JudgeNote.class, List.of(SplitNote.class, JudgeNote.class)).paletteNames(),
                "a key tool named in the palette too is there once, where the palette put it");
        assertEquals(List.of("judge_note"), new Triage(workflow, JudgeNote.class, List.of()).paletteNames(), "a palette of the key tool alone");
        assertThrows(IllegalArgumentException.class,
                () -> new DecisionThinker<Note, Verdict>(workflow, Verdict.class, JudgeNote.class, List.of(), " ") {}, "a blank objective");
        assertThrows(IllegalArgumentException.class,
                () -> new DecisionThinker<Note, Verdict>(workflow, null, JudgeNote.class, List.of(), "judge") {}, "no output type");
        assertThrows(IllegalArgumentException.class,
                () -> new DecisionThinker<Note, Verdict>(workflow, Verdict.class, null, List.of(), "judge") {}, "no key tool");
        assertThrows(IllegalArgumentException.class,
                () -> new DecisionThinker<Note, Verdict>(workflow, Verdict.class, JudgeNote.class, null, "judge") {}, "no palette list");
        // the key tool runs like any other and what it produces is put to the model for the answer
        FakeDecisionClient.policy = DecisionThinkerTest::splitThenJudge;
        Triage triage = new Triage(workflow, JudgeNote.class, List.of());
        assertInstanceOf(Tool.class, triage, "a decision thinker is a tool, so it sits in another agent's palette");
        triage.setInput(new Note("alpha"));
        ListArtifact<Verdict> answer = JobDispatcher.getInstance().submit(triage).get();
        assertEquals(List.of("judge_note", DecisionTurn.FINISH), triage.turns().stream().map(DecisionTurn::tool).toList());
        assertEquals(1, answer.getIterands().size(), "the verdict the key tool produced was selected");
        assertEquals("alpha", answer.getIterands().get(0).getAbout());
        assertThrows(IllegalArgumentException.class, () -> triage.setMaxTurns(0));
        assertThrows(IllegalArgumentException.class, () -> triage.setSelectionThreshold(1.5));
    }

    @Test
    void theInputIsNeverAnAnswerWhateverItsType() throws Exception {
        // the answer type is the input's own type: the parts a split produces are candidates, the input is not
        FakeDecisionClient.policy = request -> {
            Map<String, Answer> answers = new LinkedHashMap<>();
            Choice next = (Choice) request.questions().get("next");
            if (next != null) {
                answers.put("next", sure(next, next.options().containsKey(DecisionTurn.FINISH) ? DecisionTurn.FINISH : "split_note"));
                for (Map.Entry<String, Question> question : request.questions().entrySet()) {
                    if (question.getValue() instanceof Choice candidates && question.getKey().endsWith("_input")) {
                        answers.put(question.getKey(), sure(candidates, candidates.options().keySet().iterator().next()));
                    }
                }
            }
            for (Map.Entry<String, Question> question : request.questions().entrySet()) {
                if (question.getValue() instanceof Noul) {
                    answers.put(question.getKey(), new NoulAnswer(0.9));
                }
            }
            return answers;
        };
        Identifiable workflow = Job.workflow("test-user", "decision-thinker-input");
        DecisionThinker<Note, Note> splitter = new DecisionThinker<>(workflow, Note.class, SplitNote.class, List.of(),
                "Split the note into its parts and answer with the parts") {};
        Note whole = new Note("alpha is due; beta can wait");
        splitter.setInput(whole);
        ListArtifact<Note> answer = JobDispatcher.getInstance().submit(splitter).get();
        Choice first = (Choice) FakeDecisionClient.seen.get(0).questions().get("next");
        assertFalse(first.options().containsKey(DecisionTurn.FINISH), "nothing of the output type exists yet: the input does not count");
        assertTrue(FakeDecisionClient.seen.get(0).questions().keySet().stream().noneMatch(id -> id.startsWith("select_")),
                "no selection is asked about the input: " + FakeDecisionClient.seen.get(0).questions().keySet());
        long selections = FakeDecisionClient.seen.get(1).questions().keySet().stream().filter(id -> id.startsWith("select_")).count();
        assertEquals(2, selections, "the two parts are put to the model, the whole note is not");
        assertEquals(2, answer.getIterands().size());
        assertTrue(answer.getIterands().stream().noneMatch(note -> note == whole), "the input is never in the answer");
    }

    @Test
    void anUnreadableToolFailureIsTheSystemFailureItIs() {
        FakeDecisionClient.policy = request -> {
            Map<String, Answer> answers = new LinkedHashMap<>();
            for (Map.Entry<String, Question> question : request.questions().entrySet()) {
                if (question.getValue() instanceof Choice choice) {
                    answers.put(question.getKey(), sure(choice, choice.options().keySet().iterator().next()));
                }
                else {
                    answers.put(question.getKey(), new NoulAnswer(0.5));
                }
            }
            return answers;
        };
        Identifiable workflow = Job.workflow("test-user", "decision-thinker-broken");
        Triage triage = new Triage(workflow, BrokenJudge.class, List.of());
        triage.setInput(new Note("alpha"));
        ExecutionException failure = assertThrows(ExecutionException.class, () -> JobDispatcher.getInstance().submit(triage).get());
        boolean named = false;
        boolean readable = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            named |= cause.getMessage() != null && cause.getMessage().contains("the judge's database is gone");
            readable |= cause instanceof InvalidInputException;
        }
        assertTrue(named, "the run fails with the tool's own failure: " + failure);
        assertFalse(readable, "a bug is never fed back to the model as a verdict");
        assertTrue(triage.turns().isEmpty(), "no turn is recorded for a run that failed on its first move");
    }

    @Test
    void aDigestIsOneLineNamingTheRefTheTypeAndTheWords() {
        ArtifactRegistry registry = new ArtifactRegistry();
        Note note = new Note("x".repeat(400));
        registry.register(note);
        String digest = DecisionThinker.digest(note);
        assertTrue(digest.startsWith(note.getArtifactRef() + " (note): "), digest);
        assertTrue(digest.endsWith("..."), digest);
        assertEquals(160, DecisionThinker.DIGEST_CHARS);
        assertEquals((note.getArtifactRef() + " (note): ").length() + DecisionThinker.DIGEST_CHARS + "...".length(), digest.length(),
                "the words cut at the digest length, with an ellipsis");
        // a link reads as its title
        LinkArtifact link = new LinkArtifact();
        link.setTitle("The dealer portal changelog");
        link.setUrl("https://example.test/changelog");
        registry.register(link);
        assertTrue(DecisionThinker.digest(link).endsWith(": The dealer portal changelog"), DecisionThinker.digest(link));
        ListArtifact<Note> list = new ListArtifact<>();
        list.setIterands(List.of(note));
        list.setIterandTypeAlias("note");
        registry.register(list);
        assertTrue(DecisionThinker.digest(list).contains("a list of 1 note"), DecisionThinker.digest(list));
        // an application says what a model reads of its own type, one line, through the formatter registry
        TextFormatterRegistry.register(Note.class, n -> "a note of " + n.getText().length() + " characters\nabout " + n.getText().charAt(0));
        try {
            assertEquals(note.getArtifactRef() + " (note): a note of 400 characters about x", DecisionThinker.digest(note), "the registered words, on one line");
        }
        finally {
            TextFormatterRegistry.unregister(Note.class);
        }
    }
}
