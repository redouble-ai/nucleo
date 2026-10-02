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
import ai.redouble.nucleo.harness.observability.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.thinking.*;
import org.slf4j.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * One agent run, streamed to the browser as it happens: an observer on the runtime's message
 * bus, scoped to the run's workflow, that writes every event of the run and of every job
 * under it - the model turns, the tool calls, the sub-agents - as one line of
 * newline-delimited JSON, so a page draws the run as a timeline while it is on. Every line
 * names its job (id, parent, type, display name, the turn it belongs to), so the page
 * rebuilds the tree and shows the calls of one turn side by side. A model turn's completion
 * carries what the model decided - its reasoning, the tool calls it asked for, whether it
 * answered - and what the call cost; a tool's first line carries the arguments the model
 * gave it, paired from the turn's decision by order and name, and its completion carries
 * the result as the tool returned it. The run's totals ride on every terminal line, summed
 * the way the benchmark's rows are. The last line is the answer with the totals, or the
 * failure, written when the run's own terminal event comes through the bus - after every
 * line before it, since the bus delivers in order and the dispatcher publishes the terminal
 * event before it settles the handle. A browser that leaves stops the lines and nothing
 * else: the run goes on to its end on the runtime.
 *
 * <p>The logic is the demo's, shared by every host: a host supplies how a line reaches the
 * client ({@code sink}), how the stream is closed ({@code onEnd}), and its own JSON serializer
 * ({@code toJson}), so the wire is the framework's while the trace is one. The serializer is the
 * host's rather than the runtime's because the page reads the answer's fields as the host's
 * mapper names them ({@code skillsUsed}, not the runtime's snake case).
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-25)
 */
public final class AgentTrace implements JobObserver<JobEvent> {
    private static final Logger log = LoggerFactory.getLogger(AgentTrace.class);
    private final Function<Object, String> toJson;
    private final String workflowId;
    private final String agentJobId;
    private final Consumer<String> sink;
    private final Runnable onEnd;
    private final Map<String, String> parents = new ConcurrentHashMap<>();
    /** The tool calls a model turn decided on, keyed by the turn's parent and iteration, waiting for their jobs to be scheduled. */
    private final Map<String, Deque<ToolCall>> decided = new ConcurrentHashMap<>();
    private final RunMeasure measure = new RunMeasure();
    private volatile Map<String, Object> totals;
    private volatile boolean closed;
    private final AtomicBoolean ended = new AtomicBoolean();
    private final CountDownLatch finished = new CountDownLatch(1);

    /**
     * @param toJson     the host's JSON serializer, so the answer's fields are named as the page reads them
     * @param sink       where a rendered line goes, one call per line
     * @param onEnd      closes the stream, called once when the last line is written
     */
    public AgentTrace(Function<Object, String> toJson, String workflowId, String agentJobId, Consumer<String> sink, Runnable onEnd) {
        this.toJson = toJson;
        this.workflowId = workflowId;
        this.agentJobId = agentJobId;
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
        boolean own = snapshot.getJobId().equals(agentJobId);
        if (!own && !under(snapshot)) {
            return;
        }
        Long iteration = iteration(snapshot);
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("type", "job");
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("id", snapshot.getJobId());
        job.put("parent", snapshot.getParentJobId());
        job.put("type", snapshot.getJobType() != null ? snapshot.getJobType().name() : null);
        job.put("name", snapshot.getDisplayName());
        job.put("tool", toolName(snapshot));
        job.put("action", snapshot.getAction());
        job.put("iteration", iteration);
        job.put("own", own);
        line.put("job", job);
        line.put("at", event.timestamp().toEpochMilli());
        if (event instanceof AbstractTerminalEvent<?> terminal) {
            totals = measure.add(terminal, own);
            line.put("measured", totals);
        }
        switch (event) {
            case JobScheduled scheduled -> {
                line.put("kind", "queued");
                ToolCall call = decidedCall(snapshot, iteration);
                if (call != null) {
                    line.put("input", call.getInput());
                }
            }
            case JobStartedEvent started -> {
                line.put("kind", "started");
                line.put("attempt", started.getAttempt());
            }
            case ContentStreamEvent stream -> {
                line.put("kind", "answer");
                line.put("content", stream.chunk() != null ? stream.chunk().content() : stream.getHumanMessage());
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
                if (result instanceof ThinkingResponse<?> turn) {
                    line.put("turn", turn(turn));
                    if (turn.getToolCalls() != null && !turn.getToolCalls().isEmpty()) {
                        decided.computeIfAbsent(key(snapshot.getParentJobId(), iteration), k -> new ConcurrentLinkedDeque<>()).addAll(turn.getToolCalls());
                    }
                    List<LLMResponse<?>> responses = completed.getLlmResponses();
                    if (!responses.isEmpty()) {
                        line.put("call", RunMeasure.call(responses.get(responses.size() - 1)));
                    }
                }
                else if (!own) {
                    line.put("result", result);
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
                case JobCompletedEvent<?> completed -> complete(completed.result());
                case JobFailedEvent failed -> fail(failed.getError() != null ? failed.getError() : new IllegalStateException(failed.message()));
                case JobTimedOut timedOut -> fail(new IllegalStateException(timedOut.message()));
                case JobCancelled cancelled -> fail(new IllegalStateException(cancelled.message()));
                default -> { }
            }
        }
    }

    /** Whether the job sits under the run: its parent chain reaches the agent's job. */
    private boolean under(JobSnapshot snapshot) {
        for (String ancestor = snapshot.getParentJobId(); ancestor != null; ancestor = parents.get(ancestor)) {
            if (ancestor.equals(agentJobId)) {
                return true;
            }
        }
        return false;
    }

    /** The turn a job belongs to, as the orchestrator stamped it; null on the run's own job. */
    private static Long iteration(JobSnapshot snapshot) {
        Map<String, Object> metadata = snapshot.getMetadata();
        Object stamped = metadata != null ? metadata.get(AbstractOrchestrator.META_ITERATION) : null;
        return stamped instanceof Number number ? number.longValue() : null;
    }

    /** The name the model calls this job's tool by, from the class's own declaration; null on a job that is not a tool. */
    private static String toolName(JobSnapshot snapshot) {
        ToolName declared = snapshot.jobClass() != null ? snapshot.jobClass().getAnnotation(ToolName.class) : null;
        return declared != null ? declared.value() : null;
    }

    /**
     * The call this tool job carries out: the first call of its tool's name the turn decided
     * on and no job has claimed yet. Tool jobs are submitted in the order the model listed the
     * calls, under the same parent and turn as the model turn that decided them.
     */
    private ToolCall decidedCall(JobSnapshot snapshot, Long iteration) {
        Deque<ToolCall> pending = decided.get(key(snapshot.getParentJobId(), iteration));
        String tool = toolName(snapshot);
        if (pending == null || tool == null) {
            return null;
        }
        for (Iterator<ToolCall> calls = pending.iterator(); calls.hasNext(); ) {
            ToolCall call = calls.next();
            if (tool.equals(call.getToolName())) {
                calls.remove();
                return call;
            }
        }
        return null;
    }

    private static String key(String parentJobId, Long iteration) {
        return parentJobId + "#" + iteration;
    }

    /** What the model decided this turn: whether it answered, its reasoning, and the calls it asked for with their arguments. */
    private static Map<String, Object> turn(ThinkingResponse<?> response) {
        Map<String, Object> turn = new LinkedHashMap<>();
        turn.put("finalAnswer", response.isFinalAnswer());
        turn.put("reasoning", response.getReasoning() != null ? response.getReasoning().getUserFriendlyDescription() : null);
        List<Map<String, Object>> calls = new ArrayList<>();
        if (response.getToolCalls() != null) {
            for (ToolCall call : response.getToolCalls()) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("name", call.getToolName());
                one.put("input", call.getInput());
                calls.add(one);
            }
        }
        turn.put("toolCalls", calls);
        return turn;
    }

    /** The last line: the answer and the run's totals, then the stream closes. */
    private void complete(Object answer) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("type", "workflow_complete");
        line.put("answer", answer);
        line.put("measured", totals);
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
            sink.accept(toJson.apply(line) + "\n");
        }
        catch (UncheckedIOException | IllegalStateException gone) {
            // the browser left or the response is already closed: the run goes on for the
            // runtime's own record, nobody is reading the lines
            closed = true;
            log.info("The agent run {} lost its reader ({}); it runs to its end unwatched", workflowId, gone.getMessage());
        }
    }
}
