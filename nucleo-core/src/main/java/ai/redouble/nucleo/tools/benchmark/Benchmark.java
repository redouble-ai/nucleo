/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.benchmark;

import ai.redouble.nucleo.*;
import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.observability.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.thinking.*;
import org.slf4j.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * Any model-dependent job, raced across the models of its grade. Wraps a factory of the
 * job and takes the job's own input; runs it first as the deployment would (the reference,
 * on whatever the picker serves for the grade) - alone, because its full run is the ground
 * truth - then once per run on every open, callable entry of the grade, each pinned to its
 * entry and identical otherwise; then has the strongest model the deployment serves judge
 * each candidate run against the reference run under {@link JudgeTool}'s fixed rubric,
 * blind, one judge per run starting the moment the run lands, and scores in code where a
 * {@link Scorer} is given. The reference itself is the yardstick and is not judged.
 * Returns what the reference returned, so a workflow that wraps a step in a benchmark gets
 * the same output it would have got unwrapped; the measurements go sideways, as a
 * {@link BenchmarkReport} on the job's metadata under {@link #REPORT}, in the log as a
 * table, and from {@link #report()} after the run.
 *
 * <p>The judge sees each run in full: when the job is a thinker, its {@link AbstractThinker#transcript()}
 * - every tool it called and what the tool returned - and then its answer; the reference the
 * same way. Every candidate run is submitted at once and paced by admission, so a benchmark
 * costs the reference plus one job's worth of wall time per model, and the judges overlap
 * the race. A run that fails or is refused at the cap is a row that says so, and so is a
 * run whose judge failed; the benchmark finishes on what it has. The reference failing
 * leaves every run unjudged, with the reason on the report, and fails the benchmark the
 * way the wrapped job would have failed, after the table is logged.
 *
 * <p>The numbers come from a private {@link CostLedger} fed by the dispatcher for the
 * benchmark's own workflow, attributed to runs by lineage: a thinker's calls are its
 * LLMCall children's, and a doer's are its children's children's, so a row sums every
 * priced call under its run's root job.
 *
 * <p>The race is watchable while it runs. Every run and every judge is submitted with
 * metadata naming its place in the race - {@link #RUN_ROLE}, {@link #RUN_MODEL},
 * {@link #RUN_NUMBER} - so an observer of the workflow's events on the message bus can
 * attribute each event to a row: the run's own events by those entries, the events of
 * the calls the run makes by walking their parent chain up to it.
 *
 * @param <I> the wrapped job's input
 * @param <O> the wrapped job's output
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-17)
 */
@DisplayName(value = "Benchmark", action = "Racing a job across the models of its grade")
@ToolName("benchmark")
@ToolDescription(value = "Runs one job on every open model of its grade, several times, and reports cost, latency and judged quality per model.", readOnly = true)
@ToolWeight(type = ToolType.IN_MEMORY)
public class Benchmark<I, O> extends AbstractDoer<I, O> {
    private static final Logger log = LoggerFactory.getLogger(Benchmark.class);
    /** The metadata key the report is published under on the benchmark's own job. */
    public static final String REPORT = "benchmark";
    /** Metadata on every raced job and judge: {@link #REFERENCE}, {@link #CANDIDATE} or {@link #JUDGE}. */
    public static final String RUN_ROLE = "benchmark.role";
    /** Metadata on every candidate run and its judge: the candidate's catalog id; absent on the reference and its judge. */
    public static final String RUN_MODEL = "benchmark.model";
    /** Metadata on every raced job and judge: the run number, 1-based per candidate, 0 for the reference. */
    public static final String RUN_NUMBER = "benchmark.run";
    public static final String REFERENCE = "reference";
    public static final String CANDIDATE = "candidate";
    /** A judge carries the model and number of the run it judges. */
    public static final String JUDGE = "judge";
    /** How long past a run's completion its terminal event may take to reach this job's observer. */
    private static final Duration EVENT_LAG = Duration.ofSeconds(30);
    private final Supplier<? extends ModelDependentTool<I, O>> factory;
    private final int runs;
    private List<ModelSpec> candidates;
    private String rubric;
    private Scorer<O> scorer;
    private BenchmarkReport report;
    private String judgeModel;
    private String judgeFailure;

    /**
     * @param factory builds one fresh instance of the job per run; the benchmark sets its input and pin
     * @param runs    how many times each candidate runs the job
     */
    public Benchmark(Identifiable parent, Supplier<? extends ModelDependentTool<I, O>> factory, int runs) {
        super(parent);
        this.factory = factory;
        this.runs = runs;
    }

    /** The entries to race instead of every open, callable entry of the job's grade. */
    public void setCandidates(List<ModelSpec> candidates) {
        this.candidates = candidates;
    }

    /** What a good answer does, for the judge; without one the judge scores against the task alone. */
    public void setRubric(String rubric) {
        this.rubric = rubric;
    }

    /** A score in code next to the judge's, for outputs comparable to the reference without a model. */
    public void setScorer(Scorer<O> scorer) {
        this.scorer = scorer;
    }

    /** The report of the last run; null before the benchmark has executed. */
    public BenchmarkReport report() {
        return report;
    }

    /** One run of the job: which entry it was pinned to (null for the reference), the job itself, its handle, its row. */
    private static final class Run<O> {
        final ModelSpec candidate;
        final ModelDependentTool<?, O> job;
        final BenchmarkReport.Row row = new BenchmarkReport.Row();
        JobHandle<O> handle;
        O output;

        Run(ModelSpec candidate, ModelDependentTool<?, O> job, int number) {
            this.candidate = candidate;
            this.job = job;
            row.setReference(candidate == null);
            row.setRun(number);
            if (candidate != null) {
                row.setModelId(candidate.getId());
                row.setIdentity(candidate.getIdentity());
                row.setProvider(candidate.getProviderKey());
            }
        }

        /** The metadata naming this run's place in the race, for the job submitted in the given role. */
        Map<String, Object> place(String role) {
            Map<String, Object> place = new LinkedHashMap<>();
            place.put(RUN_ROLE, role);
            if (candidate != null) {
                place.put(RUN_MODEL, candidate.getId());
            }
            place.put(RUN_NUMBER, row.getRun());
            return place;
        }
    }

    /**
     * The dispatcher's view of this benchmark's workflow: who is whose child, which jobs
     * have reached a terminal state and how long they ran, and every call priced into a
     * ledger of the benchmark's own. Attribution to a run walks the parent chain up to the
     * run's root job.
     */
    private static final class Watch implements JobObserver<JobEvent> {
        final CostLedger ledger = new CostLedger();
        final Map<String, String> parents = new ConcurrentHashMap<>();
        final Map<String, Long> wallMs = new ConcurrentHashMap<>();
        private final String workflowId;

        Watch(String workflowId) {
            this.workflowId = workflowId;
        }

        @Override
        public Predicate<JobEvent> getPredicate() {
            return event -> workflowId.equals(event.snapshot().getWorkflowId());
        }

        @Override
        public synchronized void observe(JobEvent event) {
            JobSnapshot snapshot = event.snapshot();
            if (snapshot.getParentJobId() != null) {
                parents.put(snapshot.getJobId(), snapshot.getParentJobId());
            }
            if (event instanceof AbstractTerminalEvent<?> terminal) {
                ledger.observe(terminal);
                wallMs.put(snapshot.getJobId(), terminal.getDuration().toMillis());
                notifyAll();
            }
        }

        /** Blocks until the job's terminal event has been observed: the bus delivers after the handle completes. */
        synchronized void awaitTerminal(String jobId) throws InterruptedException, SystemException {
            Instant deadline = Instant.now().plus(EVENT_LAG);
            while (!wallMs.containsKey(jobId)) {
                long left = Duration.between(Instant.now(), deadline).toMillis();
                if (left <= 0) {
                    throw new SystemException("benchmark", "the terminal event of " + jobId + " did not reach the benchmark within " + EVENT_LAG, null);
                }
                wait(left);
            }
        }

        /** The run root this job runs under, or null when it is not under any of them. */
        String rootOf(String jobId, Set<String> roots) {
            for (String current = jobId; current != null; current = parents.get(current)) {
                if (roots.contains(current)) {
                    return current;
                }
            }
            return null;
        }
    }

    @Override
    public O execute(JobContext<O> context) throws LLMReadableCheckedException {
        Instant started = Instant.now();
        if (runs < 1) {
            throw new InvalidInputException("runs", String.valueOf(runs), "at least one run per model");
        }
        ModelDependentTool<I, O> reference = factory.get();
        if (reference.getGrade() == null) {
            throw new IllegalStateException(reference.getClass().getSimpleName() + " declares no grade; a benchmark races the models of the job's grade");
        }
        Grade grade = reference.getGrade() == Grade.CEILING ? ModelPickers.ceiling() : reference.getGrade();
        List<ModelSpec> raced = candidates != null ? candidates : candidates(grade);
        if (raced.isEmpty()) {
            throw new InvalidInputException("grade", grade.name(), "a grade with at least one open entry whose provider is configured");
        }
        Watch watch = new Watch(context.getWorkflowId());
        MessageBus.Subscription subscription = JobDispatcher.getInstance().subscribe(watch, JobEvent.class);
        try {
            return race(context, started, grade, reference, raced, watch);
        }
        finally {
            subscription.unsubscribe();
        }
    }

    private O race(JobContext<O> context, Instant started, Grade grade, ModelDependentTool<I, O> reference, List<ModelSpec> raced, Watch watch)
            throws LLMReadableCheckedException {
        // the reference runs first and alone: its full run - the tools it called, what they
        // returned, its answer - is the ground truth every candidate is judged against, so
        // it has to be in hand before the first candidate can be judged
        context.publish("Running the reference on the strongest model", 5);
        nextStep();
        List<Run<O>> all = new ArrayList<>();
        Run<O> referenceRun = new Run<>(null, reference, 0);
        reference.setInput(input);
        referenceRun.handle = submitInCurrentStep(reference, referenceRun.place(REFERENCE));
        all.add(referenceRun);
        String truth = null;
        try {
            referenceRun.output = referenceRun.handle.get();
            referenceRun.row.setSucceeded(true);
            truth = transcript(referenceRun);
        }
        catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            referenceRun.row.setFailure(reason(cause));
            judgeFailure = "the reference run failed, so there is nothing to judge against: " + reason(cause);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SystemException("benchmark", "interrupted while waiting for the reference run", e);
        }
        context.publish("Submitting " + raced.size() + " models x " + runs + " runs", 10);
        nextStep();
        for (ModelSpec candidate : raced) {
            for (int number = 1; number <= runs; number++) {
                context.checkCancellation();
                ModelDependentTool<I, O> job = factory.get();
                job.setInput(input);
                job.pinModel(candidate);
                Run<O> run = new Run<>(candidate, job, number);
                run.handle = submitInCurrentStep(job, run.place(CANDIDATE));
                all.add(run);
            }
        }
        // the candidates are taken in the order they land, not the order they were
        // submitted, so the judge starts the moment THAT run's answer lands: early answers
        // are judged while slow runs still race, a slow run early in the list holds back
        // nobody's judge, and a judge that fails costs its own run's score, never the table.
        // The judges are submitted here, on the benchmark's own thread, because only a job
        // submits children naming itself as their parent; the futures merely say which run
        // landed.
        List<Run<O>> candidates = all.subList(1, all.size());
        BlockingQueue<Run<O>> landed = new LinkedBlockingQueue<>();
        for (Run<O> run : candidates) {
            run.handle.asFuture().whenComplete((output, failure) -> landed.add(run));
        }
        int done = 0;
        Map<Run<O>, JobHandle<JudgeVerdict>> judges = new LinkedHashMap<>();
        nextStep();
        while (done < candidates.size()) {
            Run<O> run;
            try {
                run = landed.take();
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SystemException("benchmark", "interrupted while waiting for the runs", e);
            }
            try {
                run.output = run.handle.get();
                run.row.setSucceeded(true);
                if (truth != null) {
                    judges.put(run, submitInCurrentStep(judgeFor(run, truth), run.place(JUDGE)));
                }
            }
            catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                run.row.setFailure(reason(cause));
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SystemException("benchmark", "interrupted while reading a landed run of " + run.handle.getJobId(), e);
            }
            done++;
            context.publish(done + " of " + candidates.size() + " runs finished", 10 + 60 * done / candidates.size());
        }
        // the ledger fills from the bus after the handles complete; read it only once every root has landed
        Set<String> roots = new HashSet<>();
        try {
            for (Run<O> run : all) {
                watch.awaitTerminal(run.handle.getJobId());
                roots.add(run.handle.getJobId());
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SystemException("benchmark", "interrupted while waiting for the runs' terminal events", e);
        }
        measure(watch, all, roots, context.getWorkflowId());
        judge(context, judges, watch);
        if (scorer != null && referenceRun.output != null) {
            for (Run<O> run : all) {
                if (run.candidate != null && run.output != null) {
                    run.row.setScore(scorer.score(referenceRun.output, run.output));
                }
            }
        }
        report = new BenchmarkReport();
        report.setJobClass(reference.getClass().getSimpleName());
        report.setGrade(grade);
        report.setRuns(runs);
        report.setJudgeModel(judgeModel);
        report.setJudgeFailure(judgeFailure);
        for (Run<O> run : all) {
            report.getRows().add(run.row);
        }
        report.getSummaries().add(summarize(List.of(referenceRun)));
        for (ModelSpec candidate : raced) {
            List<Run<O>> own = new ArrayList<>();
            for (Run<O> run : all) {
                if (run.candidate == candidate) {
                    own.add(run);
                }
            }
            report.getSummaries().add(summarize(own));
        }
        report.setElapsedMs(Duration.between(started, Instant.now()).toMillis());
        context.putMetadata(REPORT, report);
        log.info("Benchmark finished in {} ms\n{}", report.getElapsedMs(), report.table());
        if (referenceRun.output == null) {
            try {
                return referenceRun.handle.get();
            }
            catch (ExecutionException e) {
                throw LLMReadableCheckedException.unwrap(e.getCause() != null ? e.getCause() : e);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SystemException("benchmark", "interrupted while reading the reference run", e);
            }
        }
        return referenceRun.output;
    }

    /**
     * Every entry of the grade this deployment can call: open, its provider on the classpath
     * and configured, and not needing a data-share posture the deployment has not
     * provisioned. The same test the default picker applies before it serves an entry.
     */
    public static List<ModelSpec> candidates(Grade grade) {
        List<ModelSpec> candidates = new ArrayList<>();
        for (ModelSpec spec : Models.pool(grade)) {
            if (spec.getStatus() != ModelStatus.OPEN || spec.isEmbeddings()) {
                continue;
            }
            ClientProvider<?> provider = ClientProviders.find(spec.getProviderKey());
            if (provider == null || !provider.configured()) {
                continue;
            }
            if (spec.requiresLax() && Settings.get(ModelSettings.class).mantleLaxProject == null) {
                continue;
            }
            candidates.add(spec);
        }
        return candidates;
    }

    /** Each run's calls off the private ledger, attributed by lineage to the run's root job. */
    private void measure(Watch watch, List<Run<O>> all, Set<String> roots, String workflowId) {
        Map<String, Run<O>> byRoot = new HashMap<>();
        for (Run<O> run : all) {
            byRoot.put(run.handle.getJobId(), run);
            run.row.setWallMs(watch.wallMs.get(run.handle.getJobId()));
        }
        CostLedger.Workflow spend = watch.ledger.workflow(workflowId);
        if (spend == null) {
            return;
        }
        for (CostLedger.Call call : spend.getCalls()) {
            String root = watch.rootOf(call.jobId(), roots);
            if (root == null) {
                continue;
            }
            BenchmarkReport.Row row = byRoot.get(root).row;
            row.setCalls(row.getCalls() + 1);
            row.setInputTokens(row.getInputTokens() + call.inputTokens());
            row.setOutputTokens(row.getOutputTokens() + call.outputTokens());
            row.setLatencyMs(row.getLatencyMs() + call.latencyMs());
            if (call.cost() != null) {
                row.setCurrency(call.cost().currency());
                row.setCost(new Cost(row.getCost() != null ? row.getCost() : 0.0, call.cost().currency()).plus(call.cost()).amount());
            }
            if (row.getModelId() == null) {
                row.setModelId(call.modelId());
                ModelSpec spec = Models.findSpec(call.modelId());
                if (spec != null) {
                    row.setIdentity(spec.getIdentity());
                    row.setProvider(spec.getProviderKey());
                }
            }
            if (call.servedModelId() != null) {
                row.setServedModelId(call.servedModelId());
            }
        }
        // a thinker's iterations are its direct children's calls; a one-call tool has one
        for (Run<O> run : all) {
            long iterations = 0;
            for (CostLedger.Call call : spend.getCalls()) {
                if (run.handle.getJobId().equals(call.jobId()) || run.handle.getJobId().equals(watch.parents.get(call.jobId()))) {
                    iterations++;
                }
            }
            run.row.setIterations(iterations);
        }
    }

    /**
     * A run in full, for the judge: the thinker's transcript - every tool call and what the
     * tool returned - when the job is one, then the final answer as the job returned it.
     * Nothing in it says which model ran.
     */
    private String transcript(Run<O> run) {
        StringBuilder sb = new StringBuilder();
        if (run.job instanceof AbstractThinker<?, ?> thinker && thinker.transcript() != null) {
            sb.append("TRANSCRIPT:\n").append(thinker.transcript()).append('\n');
        }
        sb.append("FINAL ANSWER:\n").append(NucleoJsonSerializer.write(run.output));
        return sb.toString();
    }

    /** One judge, primed with the reference run and one candidate run, blind: nothing in its input says which model ran either. */
    private JudgeTool judgeFor(Run<O> run, String truth) {
        JudgeInput judgeInput = new JudgeInput();
        judgeInput.setTask(NucleoJsonSerializer.write(input));
        judgeInput.setRubric(rubric);
        judgeInput.setReference(truth);
        judgeInput.setCandidate(transcript(run));
        JudgeTool judge = newJudge();
        judge.setInput(judgeInput);
        return judge;
    }

    /**
     * Each run's verdict off its own judge. A judge that fails costs its own run's score,
     * with the reason on the row; the report-level failure is set only when every judge
     * failed, since then the table has no scores to stand on.
     */
    private void judge(JobContext<O> context, Map<Run<O>, JobHandle<JudgeVerdict>> judges, Watch watch) throws SystemException {
        if (judges.isEmpty()) {
            return;
        }
        context.publish("Judging " + judges.size() + " answers", 80);
        int failed = 0;
        String failure = null;
        for (Map.Entry<Run<O>, JobHandle<JudgeVerdict>> judged : judges.entrySet()) {
            Run<O> run = judged.getKey();
            JobHandle<JudgeVerdict> handle = judged.getValue();
            JudgeVerdict verdict;
            try {
                verdict = handle.get();
                watch.awaitTerminal(handle.getJobId());
            }
            catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                log.warn("The judge of one answer failed; its row stands unjudged", cause);
                failed++;
                failure = reason(cause);
                run.row.setJudgeReason("the judge failed: " + failure);
                continue;
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SystemException("benchmark", "interrupted while waiting for the judge of " + handle.getJobId(), e);
            }
            if (verdict.getAccuracy() == null || verdict.getAccuracy() < 0 || verdict.getAccuracy() > 7
                    || verdict.getReasoning() == null || verdict.getReasoning() < 0 || verdict.getReasoning() > 3) {
                // the rubric is the stick; a verdict outside it measures nothing and says so on the row
                log.warn("The judge of one answer scored outside the rubric ({} of 7, {} of 3); its row stands unjudged", verdict.getAccuracy(), verdict.getReasoning());
                failed++;
                failure = "the judge scored outside the rubric: accuracy " + verdict.getAccuracy() + " of 7, reasoning " + verdict.getReasoning() + " of 3";
                run.row.setJudgeReason("the judge failed: " + failure);
                continue;
            }
            run.row.setJudgeAccuracy(verdict.getAccuracy());
            run.row.setJudgeReasoning(verdict.getReasoning());
            run.row.setJudgeScore(verdict.getAccuracy() + verdict.getReasoning());
            run.row.setJudgeToolFault(verdict.getToolFault() == null || verdict.getToolFault().isBlank() ? null : verdict.getToolFault());
            run.row.setJudgeReason(verdict.getReason());
            if (judgeModel == null) {
                for (CostLedger.Call call : watch.ledger.workflow(context.getWorkflowId()).getCalls()) {
                    if (call.jobId().equals(handle.getJobId())) {
                        judgeModel = call.modelId();
                    }
                }
            }
        }
        if (failed == judges.size()) {
            judgeFailure = failure;
        }
    }

    /** The judge for one of this benchmark's answers. A test overrides it to judge without a transport. */
    protected JudgeTool newJudge() {
        return new JudgeTool(this);
    }

    private BenchmarkReport.Summary summarize(List<Run<O>> runs) {
        BenchmarkReport.Summary summary = new BenchmarkReport.Summary();
        summary.setReference(runs.get(0).candidate == null);
        summary.setModelId(runs.get(0).row.getModelId());
        summary.setRuns(runs.size());
        double cost = 0;
        int priced = 0;
        long latency = 0;
        long calls = 0;
        long iterations = 0;
        double wall = 0;
        double judge = 0;
        int judged = 0;
        double score = 0;
        int scored = 0;
        int failed = 0;
        for (Run<O> run : runs) {
            BenchmarkReport.Row row = run.row;
            if (!row.isSucceeded()) {
                failed++;
            }
            if (row.getCost() != null) {
                cost += row.getCost();
                priced++;
                summary.setCurrency(row.getCurrency());
            }
            latency += row.getLatencyMs();
            calls += row.getCalls();
            iterations += row.getIterations();
            wall += row.getWallMs();
            if (row.getJudgeScore() != null) {
                judge += row.getJudgeScore();
                judged++;
            }
            if (row.getScore() != null) {
                score += row.getScore();
                scored++;
            }
        }
        int n = runs.size();
        summary.setFailed(failed);
        summary.setMeanCost(priced == 0 ? null : new Cost(cost / priced, summary.getCurrency()).amount());
        summary.setMeanWallMs(wall / n);
        summary.setMeanLatencyPerCallMs(calls == 0 ? null : (double) latency / calls);
        summary.setMeanCalls((double) calls / n);
        summary.setMeanIterations((double) iterations / n);
        summary.setMeanJudgeScore(judged == 0 ? null : judge / judged);
        summary.setMeanScore(scored == 0 ? null : score / scored);
        return summary;
    }

    private static String reason(Throwable t) {
        Throwable deepest = t;
        while (deepest.getCause() != null && deepest.getCause() != deepest) {
            deepest = deepest.getCause();
        }
        String message = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
        if (deepest != t && deepest.getMessage() != null && !message.contains(deepest.getMessage())) {
            message += " (" + deepest.getMessage() + ")";
        }
        return message;
    }
}
