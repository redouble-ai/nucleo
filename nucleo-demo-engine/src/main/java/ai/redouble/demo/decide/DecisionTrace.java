/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.decide;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.events.retry.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.decision.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.observability.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.deciding.*;
import org.slf4j.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * One decision agent run, streamed as it happens: an observer on the runtime's message bus,
 * scoped to the run's workflow, writing every event of the thinker and of every job under it
 * as one line of newline-delimited JSON. Every line names its job (id, parent, type, display
 * name, tool name, the turn it belongs to), so a page rebuilds the tree and draws each turn
 * as the decision that opened it and the tool that ran on it. A decision's completion
 * carries the whole exchange: the state the model was shown (the objective, one line per
 * artifact, the moves so far), the questions asked, and every answer with its distribution,
 * since a decision model leaves no reasoning to show and the distributions are what it
 * thought. A tool's completion carries the artifact it produced. The last line is the answer
 * (the statements selected), the run's turns as the thinker recorded them, and the totals,
 * or the failure; it is written when the run's own terminal event comes through the bus,
 * after every line before it. The lines go to any sink; a host wraps the sink in its own
 * streaming response and is told when the stream ends. A sink that refuses a line (the
 * reader left) stops the lines and nothing else: the run goes on to its end on the runtime.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public final class DecisionTrace implements JobObserver<JobEvent> {
    private static final Logger log = LoggerFactory.getLogger(DecisionTrace.class);
    private final String workflowId;
    private final String thinkerJobId;
    private final Consumer<String> sink;
    private final Runnable onEnd;
    private final Map<String, String> parents = new ConcurrentHashMap<>();
    private final Totals totals = new Totals();
    private volatile Map<String, Object> measured;
    private volatile boolean closed;
    private final AtomicBoolean ended = new AtomicBoolean();
    private final CountDownLatch finished = new CountDownLatch(1);

    /**
     * @param workflowId   the run's workflow, the scope of the events read
     * @param thinkerJobId the thinker's own job id, the root of the tree
     * @param sink         where each line goes, newline included; a sink that throws {@link UncheckedIOException}
     *                     or {@link IllegalStateException} has lost its reader
     * @param onEnd        told once, when the last line has been written
     */
    public DecisionTrace(String workflowId, String thinkerJobId, Consumer<String> sink, Runnable onEnd) {
        this.workflowId = workflowId;
        this.thinkerJobId = thinkerJobId;
        this.sink = sink;
        this.onEnd = onEnd;
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
        boolean own = snapshot.getJobId().equals(thinkerJobId);
        if (!own && !under(snapshot)) {
            return;
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("type", "job");
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("id", snapshot.getJobId());
        job.put("parent", snapshot.getParentJobId());
        job.put("type", snapshot.getJobType() != null ? snapshot.getJobType().name() : null);
        job.put("name", snapshot.getDisplayName());
        job.put("tool", toolName(snapshot));
        job.put("decision", snapshot.jobClass() != null && DecisionCall.class.isAssignableFrom(snapshot.jobClass()));
        job.put("iteration", iteration(snapshot));
        job.put("own", own);
        line.put("job", job);
        line.put("at", event.timestamp().toEpochMilli());
        if (event instanceof AbstractTerminalEvent<?> terminal) {
            measured = totals.add(terminal, own);
            line.put("measured", measured);
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
            case UserNotificationEvent note -> {
                line.put("kind", "note");
                line.put("title", note.title());
                line.put("severity", note.getSeverity() != null ? note.getSeverity().name() : null);
                line.put("message", note.getHumanMessage());
            }
            case RetryEvent retry -> {
                line.put("kind", "retry");
                line.put("retry", switch (retry) {
                    case RateLimitRetryEvent rateLimit -> "rate_limit";
                    case TransientErrorRetryEvent fault -> "upstream";
                    case ResponseCorrectionRetryEvent correction -> "correction";
                    case OutputTruncationRetryEvent truncation -> "truncation";
                });
                line.put("message", ((HumanReadable) retry).getHumanMessage());
            }
            case LimiterEvent limiter -> {
                line.put("kind", "admission");
                line.put("limiter", limiter.limiterName());
                line.put("state", limiter.type().name().toLowerCase());
                line.put("waitMs", limiter.waitNanos() / 1_000_000);
                line.put("message", limiter.message());
            }
            case JobCompletedEvent<?> completed -> {
                line.put("kind", "completed");
                Object result = completed.result();
                if (result instanceof DecisionResponse decision) {
                    line.put("decision", decision(decision));
                }
                else if (!own) {
                    line.put("result", produced(result));
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
        // the run's own terminal event ends the stream from here, on the bus's own thread: the
        // dispatcher publishes it before it settles the handle, and the bus delivers in order,
        // so every line of the run is written before the last one
        if (own) {
            switch (event) {
                case JobCompletedEvent<?> completed -> complete(completed.result(), snapshot.getMetadata());
                case JobFailedEvent failed -> fail(failed.getError() != null ? failed.getError() : new IllegalStateException(failed.message()));
                case JobTimedOut timedOut -> fail(new IllegalStateException(timedOut.message()));
                case JobCancelled cancelled -> fail(new IllegalStateException(cancelled.message()));
                default -> { }
            }
        }
    }

    /** Whether the job sits under the run: its parent chain reaches the thinker's job. */
    private boolean under(JobSnapshot snapshot) {
        for (String ancestor = snapshot.getParentJobId(); ancestor != null; ancestor = parents.get(ancestor)) {
            if (ancestor.equals(thinkerJobId)) {
                return true;
            }
        }
        return false;
    }

    /** The turn a job belongs to, as the thinker stamped it; null on the run's own job. */
    private static Long iteration(JobSnapshot snapshot) {
        Map<String, Object> metadata = snapshot.getMetadata();
        Object stamped = metadata != null ? metadata.get(AbstractOrchestrator.META_ITERATION) : null;
        return stamped instanceof Number number ? number.longValue() : null;
    }

    /** The name the model chose this job's tool by, from the class's own declaration; null on a job that is not a tool. */
    private static String toolName(JobSnapshot snapshot) {
        ToolName declared = snapshot.jobClass() != null ? snapshot.jobClass().getAnnotation(ToolName.class) : null;
        return declared != null ? declared.value() : null;
    }

    /**
     * The whole exchange of one decision: the state the model was shown, the questions asked
     * (each with its options and their descriptions), every answer with its distribution, and
     * the call's account.
     */
    private static Map<String, Object> decision(DecisionResponse response) {
        Map<String, Object> exchange = new LinkedHashMap<>();
        DecisionRequest request = response.getDecisionRequest();
        exchange.put("state", request.state());
        Map<String, Object> questions = new LinkedHashMap<>();
        for (Map.Entry<String, Question> question : request.questions().entrySet()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("instructions", question.getValue().instructions());
            switch (question.getValue()) {
                case Choice choice -> {
                    one.put("type", "choice");
                    one.put("options", choice.options());
                }
                case Noul noul -> one.put("type", "noul");
                case Score score -> one.put("type", "score");
            }
            questions.put(question.getKey(), one);
        }
        exchange.put("questions", questions);
        Map<String, Object> answers = new LinkedHashMap<>();
        if (response.getAnswers() != null) {
            for (Map.Entry<String, Answer> answer : response.getAnswers().entrySet()) {
                Map<String, Object> one = new LinkedHashMap<>();
                switch (answer.getValue()) {
                    case ChoiceAnswer choice -> {
                        one.put("choice", choice.choice());
                        one.put("probabilities", choice.probabilities());
                        one.put("confidence", choice.confidence());
                    }
                    case NoulAnswer noul -> one.put("probability", noul.probability());
                    case ScoreAnswer score -> {
                        one.put("score", score.score());
                        one.put("probabilities", score.probabilities());
                        one.put("confidence", score.confidence());
                    }
                }
                answers.put(answer.getKey(), one);
            }
        }
        exchange.put("answers", answers);
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("model", response.getModel());
        call.put("servedModel", response.getServedModelId());
        call.put("inputTokens", response.getActualInputTokens());
        call.put("latencyMs", response.getLatencyMs());
        Cost priced = Totals.price(response);
        call.put("cost", priced != null ? priced.amount() : null);
        call.put("currency", priced != null ? priced.currency() : null);
        exchange.put("call", call);
        return exchange;
    }

    /** What a tool produced, as the page shows it: the artifact's ref and digest, and a list's iterands the same way. */
    private static Map<String, Object> produced(Object result) {
        Map<String, Object> view = new LinkedHashMap<>();
        if (result instanceof Artifact artifact) {
            view.put("ref", artifact.getArtifactRef());
            view.put("digest", digest(artifact));
            if (artifact instanceof ListArtifact<?> list && list.getIterands() != null) {
                List<Map<String, Object>> iterands = new ArrayList<>();
                for (Artifact iterand : list.getIterands()) {
                    iterands.add(Map.of("ref", String.valueOf(iterand.getArtifactRef()), "digest", digest(iterand)));
                }
                view.put("iterands", iterands);
            }
        }
        else {
            view.put("digest", String.valueOf(result));
        }
        return view;
    }

    /** One line, as the thinker's state reads it: a list by its count and iterand type, anything else by its type's formatter. */
    private static String digest(Artifact artifact) {
        if (artifact instanceof ListArtifact<?> list) {
            return "a list of " + (list.getIterands() == null ? 0 : list.getIterands().size()) + " " + list.getIterandTypeAlias();
        }
        return TextFormatterRegistry.format(artifact).replaceAll("\\s+", " ").strip();
    }

    /** The last line: the answer, the run's turns and its totals, then the stream closes. */
    private void complete(Object answer, Map<String, Object> metadata) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("type", "workflow_complete");
        List<Map<String, Object>> selected = new ArrayList<>();
        if (answer instanceof ListArtifact<?> list && list.getIterands() != null) {
            for (Artifact statement : list.getIterands()) {
                selected.add(Map.of("ref", String.valueOf(statement.getArtifactRef()), "digest", digest(statement), "artifact", statement));
            }
        }
        line.put("answer", selected);
        Object turns = metadata != null ? metadata.get(DecisionThinker.OBS_TURNS) : null;
        if (turns instanceof String recorded) {
            try {
                line.put("turns", NucleoJsonSerializer.readTree(recorded));
            }
            catch (IOException e) {
                throw new UncheckedIOException("The thinker's recorded turns are not JSON", e);
            }
        }
        line.put("measured", measured);
        write(line);
        end();
    }

    /**
     * The last line when the run itself failed, then the stream closes. The run's own failed
     * event brings it here; a holder of the handle calls it only when no terminal event
     * reached the stream ({@link #awaitEnd}), so a stream never hangs open on a run that
     * ended without one.
     */
    public void fail(Throwable failure) {
        if (ended.get()) {
            return;
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("type", "workflow_failed");
        line.put("message", failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName());
        write(line);
        end();
    }

    /** Whether the stream ended within the wait: the run's own terminal event came through and wrote the last line. */
    public boolean awaitEnd(Duration wait) throws InterruptedException {
        return finished.await(wait.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void end() {
        if (ended.compareAndSet(false, true)) {
            onEnd.run();
            finished.countDown();
        }
    }

    private synchronized void write(Map<String, Object> line) {
        if (closed) {
            return;
        }
        try {
            sink.accept(NucleoJsonSerializer.writeCompact(line) + "\n");
        }
        catch (UncheckedIOException | IllegalStateException gone) {
            // the reader left: the run goes on for the runtime's own record, nobody is reading the lines
            closed = true;
            log.info("The decision run {} lost its reader ({}); it runs to its end unwatched", workflowId, gone.getMessage());
        }
    }

    /** The run's totals over its decisions: calls, input tokens, latency, cost, the model that served, the wall time. */
    static final class Totals {
        private long calls;
        private long inputTokens;
        private long latencyMs;
        private Cost cost;
        private String model;
        private String servedModel;
        private Long wallMs;

        synchronized Map<String, Object> add(AbstractTerminalEvent<?> terminal, boolean own) {
            for (LLMResponse<?> response : terminal.getLlmResponses()) {
                calls++;
                inputTokens += response.getActualInputTokens() != null ? response.getActualInputTokens() : 0;
                latencyMs += response.getLatencyMs();
                Cost priced = price(response);
                if (priced != null) {
                    cost = cost == null ? priced : cost.plus(priced);
                }
                // the catalog entry that decided, the name the page shows; the served name is the endpoint's alias for it
                if (response.getModel() != null) {
                    model = response.getModel();
                }
                if (response.getServedModelId() != null) {
                    servedModel = response.getServedModelId();
                }
            }
            if (own) {
                wallMs = terminal.getDuration().toMillis();
            }
            Map<String, Object> totals = new LinkedHashMap<>();
            totals.put("calls", calls);
            totals.put("inputTokens", inputTokens);
            totals.put("latencyMs", latencyMs);
            totals.put("cost", cost != null ? cost.amount() : null);
            totals.put("currency", cost != null ? cost.currency() : null);
            totals.put("model", model);
            totals.put("servedModel", servedModel);
            totals.put("wallMs", wallMs);
            return totals;
        }

        /** Priced from the catalog entry the call was made under; null when the entry is unknown or carries no price. */
        static Cost price(LLMResponse<?> response) {
            ModelSpec spec = response.getModel() != null ? Models.findSpec(response.getModel()) : null;
            if (spec == null) {
                return null;
            }
            return Cost.of(spec, response.getActualInputTokens() != null ? response.getActualInputTokens() : 0, 0, 0, 0);
        }
    }
}
