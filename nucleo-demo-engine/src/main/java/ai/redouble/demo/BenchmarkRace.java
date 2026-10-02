/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.events.retry.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.observability.*;
import ai.redouble.nucleo.tools.benchmark.*;
import org.slf4j.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * One benchmark's race, streamed to the browser as it runs: an observer on the runtime's
 * message bus, scoped to the race's workflow, that turns every event of the raced runs
 * and their judges - and of the calls those runs make, attributed by walking each job's
 * parent chain up to the run the {@link Benchmark} named - into one line of
 * newline-delimited JSON on the response, the way the chat endpoints stream a
 * conversation's progress over a socket. The lines borrow the chat wire's frame types:
 * a {@code status} line carrying the plan (one row per run, in race order), a
 * {@code progress} line per event - a terminal one carrying the row's spend so far, summed
 * from the responses it carries and priced from the catalog; a judge's completion carrying
 * its score and reason - and {@code workflow_complete} with the answer and the report, or
 * {@code workflow_failed} with the failure. A browser that leaves stops the lines and
 * nothing else: the race runs to its end on the runtime.
 *
 * <p>The logic is the demo's, shared by every host: a host supplies how a line reaches the
 * client ({@code sink}), how the stream is closed ({@code onEnd}), and its own JSON serializer
 * ({@code toJson}), so the wire is the framework's while the trace is one.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-25)
 */
public final class BenchmarkRace implements JobObserver<JobEvent> {
    private static final Logger log = LoggerFactory.getLogger(BenchmarkRace.class);
    /** How a raced job sits in the race: the row it belongs to and the role it plays there. */
    private record Stake(String row, String role) {}

    private final Function<Object, String> toJson;
    private final String workflowId;
    private final String benchmarkJobId;
    private final Consumer<String> sink;
    private final Runnable onEnd;
    private final Map<String, String> parents = new ConcurrentHashMap<>();
    private final Map<String, Stake> stakes = new ConcurrentHashMap<>();
    /** What each row has spent so far ({@link RunMeasure}), so the row's numbers move while the race runs and the report's ledger figures replace them when it lands. */
    private final Map<String, RunMeasure> measured = new ConcurrentHashMap<>();
    private volatile boolean closed;

    /**
     * @param toJson the host's JSON serializer
     * @param sink   where a rendered line goes, one call per line
     * @param onEnd  closes the stream, called once when the last line is written
     */
    public BenchmarkRace(Function<Object, String> toJson, String workflowId, String benchmarkJobId, Consumer<String> sink, Runnable onEnd) {
        this.toJson = toJson;
        this.workflowId = workflowId;
        this.benchmarkJobId = benchmarkJobId;
        this.sink = sink;
        this.onEnd = onEnd;
    }

    /** The row key a run goes by on the wire: the reference by name, a candidate run by model and number. */
    static String rowKey(String model, int run) {
        return model == null ? Benchmark.REFERENCE : model + "#" + run;
    }

    /** The first line: every row the race will have, in race order, before any of them has an event. */
    public void plan(List<ModelSpec> raced, int runs) {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row(null, 0));
        for (ModelSpec candidate : raced) {
            for (int number = 1; number <= runs; number++) {
                rows.add(row(candidate.getId(), number));
            }
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("type", "status");
        line.put("status", "plan");
        line.put("rows", rows);
        write(line);
    }

    private static Map<String, Object> row(String model, int run) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", rowKey(model, run));
        row.put("model", model);
        row.put("run", run);
        row.put("role", model == null ? Benchmark.REFERENCE : Benchmark.CANDIDATE);
        return row;
    }

    @Override
    public Predicate<JobEvent> getPredicate() {
        return event -> event.snapshot() != null && workflowId.equals(event.snapshot().getWorkflowId());
    }

    @Override
    public void observe(JobEvent event) {
        JobSnapshot snapshot = event.snapshot();
        if (snapshot.getParentJobId() != null) {
            parents.put(snapshot.getJobId(), snapshot.getParentJobId());
        }
        Stake stake = stakes.get(snapshot.getJobId());
        Map<String, Object> place = snapshot.getMetadata();
        if (stake == null && place != null && place.get(Benchmark.RUN_ROLE) != null) {
            String model = (String) place.get(Benchmark.RUN_MODEL);
            int run = ((Number) place.get(Benchmark.RUN_NUMBER)).intValue();
            stake = new Stake(rowKey(model, run), (String) place.get(Benchmark.RUN_ROLE));
            stakes.put(snapshot.getJobId(), stake);
        }
        boolean own = stake != null;
        for (String ancestor = snapshot.getParentJobId(); stake == null && ancestor != null; ancestor = parents.get(ancestor)) {
            stake = stakes.get(ancestor);
        }
        if (stake == null) {
            // the benchmark's own progress is the race's headline; every other job of the
            // workflow is outside the race
            if (snapshot.getJobId().equals(benchmarkJobId) && event instanceof JobProgressEvent<?> progress) {
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("type", "status");
                line.put("status", "overall");
                line.put("message", progress.getHumanMessage());
                line.put("percent", progress.getProgressPercent());
                write(line);
            }
            return;
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("type", "progress");
        line.put("row", stake.row());
        line.put("role", stake.role());
        line.put("child", !own);
        line.put("job", snapshot.getDisplayName());
        line.put("at", event.timestamp().toEpochMilli());
        // a row's spend moves with every terminal event under it; a judge's calls are the
        // benchmark's, never the row's, exactly as the report's ledger attributes them
        if (event instanceof AbstractTerminalEvent<?> terminal && !Benchmark.JUDGE.equals(stake.role())) {
            line.put("measured", measured.computeIfAbsent(stake.row(), key -> new RunMeasure()).add(terminal, own));
        }
        switch (event) {
            case JobScheduled scheduled -> line.put("kind", "queued");
            case JobStartedEvent started -> {
                line.put("kind", "started");
                line.put("attempt", started.getAttempt());
            }
            case JobProgressEvent<?> progress -> {
                line.put("kind", "progress");
                line.put("message", progress.getHumanMessage());
                line.put("percent", progress.getProgressPercent());
            }
            case RetryEvent retry -> {
                // an upstream re-run (the provider failed or throttled) and a correction (the
                // model's own answer was re-asked) are different stories, and the board tells them apart
                line.put("kind", "retry");
                line.put("retry", switch (retry) {
                    case RateLimitRetryEvent rateLimit -> "rate_limit";
                    case TransientErrorRetryEvent fault -> "upstream";
                    case ResponseCorrectionRetryEvent correction -> "correction";
                    case OutputTruncationRetryEvent truncation -> "truncation";
                });
                line.put("message", ((HumanReadable) retry).getHumanMessage());
            }
            case JobCompletedEvent<?> completed -> {
                line.put("kind", "completed");
                // the judge's own completion carries its verdict: the row is scored the moment it lands
                if (own && completed.result() instanceof JudgeVerdict verdict && verdict.getAccuracy() != null && verdict.getReasoning() != null) {
                    line.put("score", verdict.getAccuracy() + verdict.getReasoning());
                    line.put("accuracy", verdict.getAccuracy());
                    line.put("reasoning", verdict.getReasoning());
                    line.put("toolFault", verdict.getToolFault());
                    line.put("reason", verdict.getReason());
                }
            }
            case JobFailedEvent failed -> {
                line.put("kind", "failed");
                line.put("message", failed.getError() != null ? failed.getError().getMessage() : failed.message());
            }
            case JobTimedOut timedOut -> {
                line.put("kind", "timed_out");
                line.put("message", timedOut.message());
            }
            case JobCancelled cancelled -> {
                line.put("kind", "cancelled");
                line.put("message", cancelled.message());
            }
            default -> {
                return;
            }
        }
        write(line);
    }

    /** The last line: the answer and the report, then the stream closes. */
    public void complete(BenchmarkOutcome outcome) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("type", "workflow_complete");
        line.put("answer", outcome.answer());
        line.put("report", outcome.report());
        write(line);
        onEnd.run();
    }

    /** The last line when the benchmark itself failed, then the stream closes. */
    public void fail(Throwable failure) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("type", "workflow_failed");
        line.put("message", failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName());
        write(line);
        onEnd.run();
    }

    private synchronized void write(Map<String, Object> line) {
        if (closed) {
            return;
        }
        try {
            sink.accept(toJson.apply(line) + "\n");
        }
        catch (UncheckedIOException | IllegalStateException gone) {
            // the browser left or the response is already closed: the race runs on for the
            // runtime's own record, nobody is reading the lines
            closed = true;
            log.info("The benchmark race {} lost its reader ({}); it runs to its end unwatched", workflowId, gone.getMessage());
        }
    }
}
