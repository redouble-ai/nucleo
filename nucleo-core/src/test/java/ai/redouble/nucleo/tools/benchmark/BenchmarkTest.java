/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.benchmark;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.observability.*;
import ai.redouble.nucleo.tools.*;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A benchmark through the dispatcher on a job that answers with the model it was served:
 * the reference runs unpinned and gets the picker's choice for the grade, every candidate
 * row gets its pinned entry exactly, the private ledger attributes each run's calls to its
 * row, each answer is judged blind by its own judge and the score lands on its row, a
 * judge that fails costs only its own row's score, the scorer runs against the reference,
 * a candidate that fails is a row that says so, every run and judge is submitted naming
 * its place in the race for an observer of the workflow's events, and the benchmark
 * returns what the reference returned.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-17)
 */
class BenchmarkTest {
    @BeforeAll
    static void startDispatcher() {
        JobDispatcher.getInstance().start();
    }

    public static class Question {
        private String text;
        /** The model id the job refuses to answer on, so a candidate can be made to fail. */
        private String failOn;
        /** The model id the job answers slowly on, so a slow run can be watched not holding the others' judges. */
        private String slowOn;

        public String getText() {return text;}

        public void setText(String text) {this.text = text;}

        public String getFailOn() {return failOn;}

        public void setFailOn(String failOn) {this.failOn = failOn;}

        public String getSlowOn() {return slowOn;}

        public void setSlowOn(String slowOn) {this.slowOn = slowOn;}
    }

    public static class Answer {
        private String model;

        public String getModel() {return model;}

        public void setModel(String model) {this.model = model;}
    }

    /** Answers with the model its seat resolved to, and books one priced call on it so the ledger has a row. */
    @ToolName("echo_model")
    @ToolDescription(value = "Answers with the model it was served.", readOnly = true)
    static final class EchoTool extends AbstractModelDependentTool<Question, Answer> {
        private ModelBinding binding;

        EchoTool(Identifiable parent) {
            super(parent, Grade.SMALL);
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = new JobRequirements();
            req.setRequiresTransaction(false);
            req.setReadOnly(true);
            binding = wireConversation(req, Depth.IMMEDIATE, OutputDeclaration.of(OutputSize.VERDICT), Answer.class, "echo the model");
            return req;
        }

        @Override
        public Answer execute(JobResources resources, JobContext<Answer> context) throws LLMReadableCheckedException {
            String model = binding.getModel().getId();
            if (model.equals(input.getFailOn())) {
                throw new InvalidInputException("failOn", model, "any model but this one");
            }
            if (model.equals(input.getSlowOn())) {
                try {
                    Thread.sleep(1500);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InvalidInputException("slowOn", model, "a run left to finish");
                }
            }
            context.addLlmResponse(canned(request(), model, 1000, 50));
            Answer answer = new Answer();
            answer.setModel(model);
            return answer;
        }
    }

    /** The judge without a transport: pinned below the ceiling so the test envelope admits it, scoring by a rule the test knows. */
    static final class RuleJudge extends JudgeTool {
        private final String favourite;

        RuleJudge(Identifiable parent, String favourite) {
            super(parent);
            this.favourite = favourite;
            pinModel(TestModels.grade(Grade.XL));
        }

        @Override
        public JudgeVerdict execute(JobResources resources, JobContext<JudgeVerdict> context) {
            context.addLlmResponse(canned(request(), pinnedModel().getId(), 3000, 200));
            JudgeVerdict verdict = new JudgeVerdict();
            boolean favoured = input.getCandidate().contains(favourite);
            verdict.setAccuracy(favoured ? 7.0 : 3.0);
            verdict.setReasoning(favoured ? 2.0 : 1.0);
            verdict.setReason(favoured ? "the favourite" : "not the favourite");
            return verdict;
        }
    }

    /** A judge that refuses one answer and scores the rest, so one judge's failure can be watched staying on its own row. */
    static final class FlakyJudge extends JudgeTool {
        private final String refused;

        FlakyJudge(Identifiable parent, String refused) {
            super(parent);
            this.refused = refused;
            pinModel(TestModels.grade(Grade.XL));
        }

        @Override
        public JudgeVerdict execute(JobResources resources, JobContext<JudgeVerdict> context) throws LLMReadableCheckedException {
            if (input.getCandidate().contains(refused)) {
                throw new InvalidInputException("candidate", input.getCandidate(), "an answer this judge can read");
            }
            context.addLlmResponse(canned(request(), pinnedModel().getId(), 3000, 200));
            JudgeVerdict verdict = new JudgeVerdict();
            verdict.setAccuracy(5.0);
            verdict.setReasoning(2.0);
            verdict.setReason("readable");
            return verdict;
        }
    }

    static <T> LLMResponse<T> canned(LLMRequest<T> request, String model, int inputTokens, int outputTokens) {
        LLMResponse<T> response = new LLMResponse<>(request);
        response.setModel(model);
        response.setActualInputTokens(inputTokens);
        response.setActualOutputTokens(outputTokens);
        response.setStartTime(Instant.now().minusMillis(250));
        response.setEndTime(Instant.now());
        return response;
    }

    private static Question question(String failOn) {
        Question question = new Question();
        question.setText("which model are you");
        question.setFailOn(failOn);
        return question;
    }

    @Test
    void everyRowIsItsPinnedEntryAndTheReferenceIsThePickersChoice() throws Exception {
        Identifiable workflow = Job.workflow("test-user", "benchmark");
        ModelSpec small = TestModels.small();
        ModelSpec medium = TestModels.grade(Grade.MEDIUM);
        Benchmark<Question, Answer> benchmark = new Benchmark<>(workflow, () -> new EchoTool(workflow), 2) {
            @Override
            protected JudgeTool newJudge() {
                return new RuleJudge(this, medium.getId());
            }
        };
        benchmark.setCandidates(List.of(small, medium));
        benchmark.setScorer((reference, candidate) -> reference.getModel().equals(candidate.getModel()) ? 1.0 : 0.0);
        benchmark.setInput(question(null));
        Answer answer = JobDispatcher.getInstance().submit(benchmark).get();
        assertEquals(small.getId(), answer.getModel(), "the benchmark returns the reference's answer, served by the picker for the grade");
        BenchmarkReport report = benchmark.report();
        assertNotNull(report);
        assertEquals("EchoTool", report.getJobClass());
        assertEquals(Grade.SMALL, report.getGrade());
        assertEquals(5, report.getRows().size(), "one reference and two candidates times two runs");
        BenchmarkReport.Row reference = report.getRows().get(0);
        assertTrue(reference.isReference());
        assertEquals(small.getId(), reference.getModelId(), "the reference row names the model the ledger saw it call");
        for (BenchmarkReport.Row row : report.getRows()) {
            assertTrue(row.isSucceeded(), row.getFailure());
            assertEquals(1, row.getCalls(), "one call per run, attributed by lineage");
            assertEquals(1, row.getIterations());
            assertEquals(1000, row.getInputTokens());
            assertEquals(50, row.getOutputTokens());
            assertNotNull(row.getCost(), "priced from the catalog");
            assertEquals("USD", row.getCurrency());
            assertTrue(row.getLatencyMs() >= 200, "latency off the response");
            if (row.isReference()) {
                assertNull(row.getJudgeScore(), "the reference is the yardstick, never judged");
            }
            else {
                assertNotNull(row.getJudgeScore(), "every candidate answer judged");
            }
            if (!row.isReference()) {
                assertNotNull(row.getScore(), "every candidate scored against the reference");
            }
        }
        List<BenchmarkReport.Row> mediumRows = report.getRows().stream().filter(r -> medium.getId().equals(r.getModelId())).toList();
        List<BenchmarkReport.Row> smallRows = report.getRows().stream().filter(r -> !r.isReference() && small.getId().equals(r.getModelId())).toList();
        assertEquals(2, mediumRows.size());
        assertEquals(2, smallRows.size());
        for (BenchmarkReport.Row row : mediumRows) {
            assertEquals(9.0, row.getJudgeScore(), "the judge's score, accuracy plus reasoning, reached the row whose run it read");
            assertEquals(7.0, row.getJudgeAccuracy());
            assertEquals(2.0, row.getJudgeReasoning());
            assertEquals(0.0, row.getScore(), "the scorer compared it to the reference's answer");
        }
        for (BenchmarkReport.Row row : smallRows) {
            assertEquals(4.0, row.getJudgeScore());
            assertEquals(1.0, row.getScore());
        }
        assertEquals(TestModels.grade(Grade.XL).getId(), report.getJudgeModel(), "the judge's own call is on the ledger, not on any row");
        assertNull(report.getJudgeFailure());
        assertEquals(3, report.getSummaries().size());
        assertTrue(report.getSummaries().get(0).isReference());
        BenchmarkReport.Summary mediumSummary = report.getSummaries().get(2);
        assertEquals(medium.getId(), mediumSummary.getModelId());
        assertEquals(2, mediumSummary.getRuns());
        assertEquals(0, mediumSummary.getFailed());
        assertEquals(9.0, mediumSummary.getMeanJudgeScore());
        assertEquals(0.0, mediumSummary.getMeanScore());
        assertEquals(1.0, mediumSummary.getMeanCalls());
        assertNotNull(mediumSummary.getMeanCost());
        assertTrue(report.table().contains("* " + small.getId()), report.table());
    }

    @Test
    void everyRunAndJudgeIsSubmittedNamingItsPlaceInTheRace() throws Exception {
        Identifiable workflow = Job.workflow("test-user", "benchmark-places");
        ModelSpec small = TestModels.small();
        ModelSpec medium = TestModels.grade(Grade.MEDIUM);
        // what each started job of the race carried: the observer sees the run's own events
        // with the entries on the snapshot, the way a page streaming the race reads them
        Map<String, Map<String, Object>> places = new ConcurrentHashMap<>();
        MessageBus.Subscription subscription = JobDispatcher.getInstance().subscribe(new JobObserver<JobStartedEvent>() {
            @Override
            public java.util.function.Predicate<JobStartedEvent> getPredicate() {
                return event -> workflow.getWorkflowId().equals(event.snapshot().getWorkflowId());
            }

            @Override
            public void observe(JobStartedEvent event) {
                if (event.snapshot().getMetadata().containsKey(Benchmark.RUN_ROLE)) {
                    places.put(event.snapshot().getJobId(), event.snapshot().getMetadata());
                }
            }
        }, JobStartedEvent.class);
        try {
            Benchmark<Question, Answer> benchmark = new Benchmark<>(workflow, () -> new EchoTool(workflow), 2) {
                @Override
                protected JudgeTool newJudge() {
                    return new RuleJudge(this, medium.getId());
                }
            };
            benchmark.setCandidates(List.of(small, medium));
            benchmark.setInput(question(null));
            JobDispatcher.getInstance().submit(benchmark).get();
            // the bus delivers on its own threads: one reference, four runs, five judges
            for (int waited = 0; places.size() < 9 && waited < 100; waited++) {
                Thread.sleep(50);
            }
            assertEquals(9, places.size(), "one reference, two runs of two candidates, and a judge per candidate answer, each named: " + places);
            Map<String, Object> reference = places.values().stream().filter(p -> Benchmark.REFERENCE.equals(p.get(Benchmark.RUN_ROLE))).findFirst().orElseThrow();
            assertNull(reference.get(Benchmark.RUN_MODEL), "the reference names no candidate");
            assertEquals(0, reference.get(Benchmark.RUN_NUMBER));
            for (ModelSpec candidate : List.of(small, medium)) {
                for (int number = 1; number <= 2; number++) {
                    for (String role : List.of(Benchmark.CANDIDATE, Benchmark.JUDGE)) {
                        int run = number;
                        assertTrue(places.values().stream().anyMatch(p -> role.equals(p.get(Benchmark.RUN_ROLE))
                                        && candidate.getId().equals(p.get(Benchmark.RUN_MODEL)) && Integer.valueOf(run).equals(p.get(Benchmark.RUN_NUMBER))),
                                role + " of " + candidate.getId() + " run " + run + " was submitted naming it: " + places);
                    }
                }
            }
            assertTrue(places.values().stream().noneMatch(p -> Benchmark.JUDGE.equals(p.get(Benchmark.RUN_ROLE)) && p.get(Benchmark.RUN_MODEL) == null),
                    "the reference is the yardstick and gets no judge");
        }
        finally {
            subscription.unsubscribe();
        }
    }

    @Test
    void theReferenceRunsFirstAndASlowRunHoldsBackNoOtherRunsJudge() throws Exception {
        // the picker's SMALL choice answers slowly: as the reference it runs first and alone,
        // and as a candidate it must not hold back the medium candidate's judge, which starts
        // the moment the medium run lands
        Identifiable workflow = Job.workflow("test-user", "benchmark-slow-run");
        ModelSpec small = TestModels.small();
        ModelSpec medium = TestModels.grade(Grade.MEDIUM);
        Map<String, Instant> judgeStarted = new ConcurrentHashMap<>();
        Map<String, Instant> candidateStarted = new ConcurrentHashMap<>();
        Map<String, Instant> candidateCompleted = new ConcurrentHashMap<>();
        Map<String, Instant> referenceCompleted = new ConcurrentHashMap<>();
        MessageBus.Subscription subscription = JobDispatcher.getInstance().subscribe(new JobObserver<JobEvent>() {
            @Override
            public java.util.function.Predicate<JobEvent> getPredicate() {
                return event -> event.snapshot() != null && workflow.getWorkflowId().equals(event.snapshot().getWorkflowId());
            }

            @Override
            public void observe(JobEvent event) {
                Map<String, Object> place = event.snapshot().getMetadata();
                String model = (String) place.get(Benchmark.RUN_MODEL);
                Object role = place.get(Benchmark.RUN_ROLE);
                if (event instanceof JobStartedEvent && Benchmark.JUDGE.equals(role)) {
                    judgeStarted.put(model, event.timestamp());
                }
                if (event instanceof JobStartedEvent && Benchmark.CANDIDATE.equals(role)) {
                    candidateStarted.put(model, event.timestamp());
                }
                if (event instanceof JobCompletedEvent<?> && Benchmark.CANDIDATE.equals(role)) {
                    candidateCompleted.put(model, event.timestamp());
                }
                if (event instanceof JobCompletedEvent<?> && Benchmark.REFERENCE.equals(role)) {
                    referenceCompleted.put(Benchmark.REFERENCE, event.timestamp());
                }
            }
        }, JobEvent.class);
        try {
            Benchmark<Question, Answer> benchmark = new Benchmark<>(workflow, () -> new EchoTool(workflow), 1) {
                @Override
                protected JudgeTool newJudge() {
                    return new RuleJudge(this, medium.getId());
                }
            };
            benchmark.setCandidates(List.of(small, medium));
            Question question = question(null);
            question.setSlowOn(small.getId());
            benchmark.setInput(question);
            JobDispatcher.getInstance().submit(benchmark).get();
            for (int waited = 0; (judgeStarted.size() < 2 || candidateCompleted.size() < 2 || referenceCompleted.isEmpty()) && waited < 100; waited++) {
                Thread.sleep(50);
            }
            assertTrue(referenceCompleted.get(Benchmark.REFERENCE).isBefore(candidateStarted.get(medium.getId())),
                    "the reference ran first and alone: it finished before any candidate started");
            assertTrue(judgeStarted.get(medium.getId()).isBefore(candidateCompleted.get(small.getId())),
                    "the medium candidate's judge started while the slow small candidate was still running: judge at "
                            + judgeStarted.get(medium.getId()) + ", the slow run finished at " + candidateCompleted.get(small.getId()));
            assertEquals(9.0, benchmark.report().getRows().get(2).getJudgeScore(), "and its verdict landed on its row");
        }
        finally {
            subscription.unsubscribe();
        }
    }

    @Test
    void aFailingCandidateIsARowThatSaysSoAndTheJudgeSeesOnlyAnswers() throws Exception {
        Identifiable workflow = Job.workflow("test-user", "benchmark-failing");
        ModelSpec small = TestModels.small();
        ModelSpec medium = TestModels.grade(Grade.MEDIUM);
        Benchmark<Question, Answer> benchmark = new Benchmark<>(workflow, () -> new EchoTool(workflow), 1) {
            @Override
            protected JudgeTool newJudge() {
                return new RuleJudge(this, small.getId());
            }
        };
        benchmark.setCandidates(List.of(small, medium));
        benchmark.setInput(question(medium.getId()));
        assertEquals(small.getId(), JobDispatcher.getInstance().submit(benchmark).get().getModel());
        BenchmarkReport report = benchmark.report();
        BenchmarkReport.Row failed = report.getRows().get(2);
        assertEquals(medium.getId(), failed.getModelId());
        assertFalse(failed.isSucceeded());
        assertTrue(failed.getFailure().contains("any model but this one"), failed.getFailure());
        assertNull(failed.getJudgeScore(), "nothing to judge");
        assertEquals(0, failed.getCalls(), "it failed before booking a call");
        assertEquals(1, report.getSummaries().get(2).getFailed());
        assertNull(report.getSummaries().get(2).getMeanJudgeScore());
        assertNull(report.getRows().get(0).getJudgeScore(), "the reference is the yardstick, never judged");
        assertEquals(9.0, report.getRows().get(1).getJudgeScore());
    }

    @Test
    void aReferenceThatFailsFailsTheBenchmarkAfterTheTableIsLogged() {
        Identifiable workflow = Job.workflow("test-user", "benchmark-reference-fails");
        ModelSpec small = TestModels.small();
        Benchmark<Question, Answer> benchmark = new Benchmark<>(workflow, () -> new EchoTool(workflow), 1) {
            @Override
            protected JudgeTool newJudge() {
                return new RuleJudge(this, small.getId());
            }
        };
        benchmark.setCandidates(List.of(TestModels.grade(Grade.MEDIUM)));
        benchmark.setInput(question(small.getId()));
        ExecutionException failure = assertThrows(ExecutionException.class, () -> JobDispatcher.getInstance().submit(benchmark).get());
        assertTrue(String.valueOf(failure.getCause().getMessage()).contains("any model but this one"), String.valueOf(failure.getCause()));
        BenchmarkReport report = benchmark.report();
        assertNotNull(report, "the report is complete before the reference's failure is rethrown");
        assertFalse(report.getRows().get(0).isSucceeded());
        assertTrue(report.getRows().get(1).isSucceeded(), "the candidates still raced");
        assertNull(report.getRows().get(1).getJudgeScore(), "but nothing was judged: there is no ground truth");
        assertTrue(report.getJudgeFailure().contains("nothing to judge against"), report.getJudgeFailure());
    }

    @Test
    void aJudgeThatFailsCostsItsOwnRowsScoreAndNoOther() throws Exception {
        Identifiable workflow = Job.workflow("test-user", "benchmark-flaky-judge");
        ModelSpec small = TestModels.small();
        ModelSpec medium = TestModels.grade(Grade.MEDIUM);
        Benchmark<Question, Answer> benchmark = new Benchmark<>(workflow, () -> new EchoTool(workflow), 1) {
            @Override
            protected JudgeTool newJudge() {
                return new FlakyJudge(this, medium.getId());
            }
        };
        benchmark.setCandidates(List.of(small, medium));
        benchmark.setInput(question(null));
        JobDispatcher.getInstance().submit(benchmark).get();
        BenchmarkReport report = benchmark.report();
        BenchmarkReport.Row unjudged = report.getRows().get(2);
        assertEquals(medium.getId(), unjudged.getModelId());
        assertTrue(unjudged.isSucceeded(), "the run itself succeeded; only its judge failed");
        assertNull(unjudged.getJudgeScore());
        assertTrue(unjudged.getJudgeReason().startsWith("the judge failed: "), unjudged.getJudgeReason());
        assertTrue(unjudged.getJudgeReason().contains("an answer this judge can read"), unjudged.getJudgeReason());
        assertNull(report.getRows().get(0).getJudgeScore(), "the reference is the yardstick, never judged");
        assertEquals(7.0, report.getRows().get(1).getJudgeScore(), "the other candidate's judge scored");
        assertNull(report.getJudgeFailure(), "a report-level judge failure means every judge failed, and here one did not");
        assertEquals(TestModels.grade(Grade.XL).getId(), report.getJudgeModel(), "the judge model comes off a judge that succeeded");
    }

    @Test
    void aJudgeTheDeploymentCannotServeLeavesTheRowsUnjudged() throws Exception {
        // the test picker's ceiling entry requires a data-share posture the test envelope
        // refuses, so the gate's walk serves the judge from the rung below - whose provider
        // this test deployment cannot call; either way the judge fails and the rows stand
        Identifiable workflow = Job.workflow("test-user", "benchmark-no-judge");
        Benchmark<Question, Answer> benchmark = new Benchmark<>(workflow, () -> new EchoTool(workflow), 1);
        benchmark.setCandidates(List.of(TestModels.grade(Grade.MEDIUM)));
        benchmark.setInput(question(null));
        assertEquals(TestModels.small().getId(), JobDispatcher.getInstance().submit(benchmark).get().getModel());
        BenchmarkReport report = benchmark.report();
        assertNotNull(report.getJudgeFailure());
        assertTrue(report.getJudgeFailure().contains("anthropic-bedrock-mantle"),
                "the walk moved the judge off the refused ceiling entry onto the best permitted rung: " + report.getJudgeFailure());
        assertNull(report.getJudgeModel());
        for (BenchmarkReport.Row row : report.getRows()) {
            assertTrue(row.isSucceeded());
            assertNull(row.getJudgeScore());
        }
        assertTrue(report.table().contains("judge: "), report.table());
    }

    @Test
    void theTablePrintsMoneyAsMoneyMillisecondsWholeAndGroupedAndColoursByOutcome() {
        BenchmarkReport report = new BenchmarkReport();
        report.setJobClass("EchoTool");
        report.setGrade(Grade.SMALL);
        report.setRuns(4);
        BenchmarkReport.Summary cheap = new BenchmarkReport.Summary();
        cheap.setModelId("cheap");
        cheap.setRuns(4);
        cheap.setFailed(0);
        cheap.setMeanCost(0.0036);
        cheap.setCurrency("USD");
        cheap.setMeanWallMs(11963.5);
        cheap.setMeanLatencyPerCallMs(2882.3);
        cheap.setMeanCalls(4.0);
        cheap.setMeanIterations(2.5);
        cheap.setMeanJudgeScore(10.0);
        BenchmarkReport.Summary dear = new BenchmarkReport.Summary();
        dear.setModelId("dear");
        dear.setRuns(4);
        dear.setFailed(1);
        dear.setMeanCost(1234.5);
        dear.setCurrency("EUR");
        dear.setMeanWallMs(10868.0);
        dear.setMeanLatencyPerCallMs(4162.2);
        dear.setMeanCalls(3.0);
        dear.setMeanIterations(3.0);
        dear.setMeanJudgeScore(0.0);
        BenchmarkReport.Summary broken = new BenchmarkReport.Summary();
        broken.setModelId("broken");
        broken.setRuns(4);
        broken.setFailed(3);
        report.getSummaries().addAll(List.of(cheap, dear, broken));
        String table = report.table();
        assertTrue(table.contains("$0.0036"), table);
        assertTrue(table.contains("€1,234.5000"), table);
        assertTrue(table.contains("11,964") && table.contains("2,882") && table.contains("10,868"), "milliseconds whole and grouped: " + table);
        assertTrue(table.contains("     4 ") && table.contains("   2.5 ") && table.contains("    10"), "whole means as integers, others with one decimal: " + table);
        assertFalse(table.contains("4.0") || table.contains("3.0 "), table);
        assertTrue(table.contains("[38;5;46m     0[0m"), "no failures is green: " + table);
        assertTrue(table.contains("[38;5;208m     1[0m"), "one failure in four is orange: " + table);
        assertTrue(table.contains("[38;5;196m     3[0m"), "three failures in four is red: " + table);
        assertTrue(table.contains("[38;5;46m     $0.0036[0m"), "the cheapest cost is green: " + table);
        assertTrue(table.contains("[38;5;196m €1,234.5000[0m"), "the dearest cost is red: " + table);
        assertTrue(table.contains("[38;5;46m    10[0m"), "a judge score of ten is green: " + table);
        assertTrue(table.contains("[38;5;196m     0[0m      -"), "a judge score of zero is red: " + table);
    }

    @Test
    void noCandidateOfTheGradeIsRefusedBeforeAnyRun() {
        Identifiable workflow = Job.workflow("test-user", "benchmark-no-candidates");
        Benchmark<Question, Answer> benchmark = new Benchmark<>(workflow, () -> new EchoTool(workflow), 1);
        benchmark.setCandidates(List.of());
        benchmark.setInput(question(null));
        ExecutionException failure = assertThrows(ExecutionException.class, () -> JobDispatcher.getInstance().submit(benchmark).get());
        assertTrue(String.valueOf(failure.getCause().getMessage()).contains("at least one open entry"), String.valueOf(failure.getCause()));
        assertNull(benchmark.report());
    }
}
