/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.observability;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import org.slf4j.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * Cost accounting in memory: every call a finished job made, priced from the catalog in
 * the entry's own currency, kept per workflow, per model and per job, and the spend caps
 * workflows run under. Subscribe it to the dispatcher for terminal events and register it
 * as the dispatcher's {@link SpendGate}, and a workflow's spend is readable while it runs
 * and enforced before each of its jobs acquires anything.
 *
 * <p>A call on an entry with no price is counted, with its tokens, and reported unpriced;
 * nothing is priced at a guess. A call on a model the catalog does not know is counted the
 * same way under the model id the response named, with a warning; a call that named no
 * model at all is counted under {@code (unknown)}. Sums are per currency and never converted, so a workflow
 * runs under one cap per currency it pays in: a deployment paying Bedrock in dollars and
 * Azure in euros caps each, and each cap is judged against the spend in its own currency.
 * Under a cap, a job whose reservation would cross it is refused, and so is a job whose
 * model is priced in a currency the workflow holds no cap for, or not priced at all,
 * because a spend nobody can state is not a spend a cap can admit. A cap on a workflow is
 * never lowered by the ledger; the caller that set it removes it.
 *
 * <p>The gate counts what is in flight. A workflow fans out, so at the moment a job is
 * admitted its siblings' calls are running and have not been priced yet; the gate holds
 * each admitted reservation as committed until that job finishes, and judges a newcomer
 * against spent plus committed. Even so, staying under a cap is best effort, and the cap
 * is a ceiling on reservations rather than on the invoice: a reservation is the input as
 * the runtime counts it plus the output as declared, and a provider's bill can differ from
 * both by the tokens it counts differently and the output it serves past the declaration.
 * The overshoot is bounded by one such difference per call, never by the fan-out.
 *
 * <p>Attempts count: a call that ran and failed spent its tokens, so a failed job's
 * responses are priced like a completed one's.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
public class CostLedger implements JobObserver<JobEvent>, SpendGate {
    private static final Logger log = LoggerFactory.getLogger(CostLedger.class);
    private final Map<String, Workflow> workflows = new ConcurrentHashMap<>();
    /** The caps a workflow runs under, one per currency: a deployment paying two providers in two currencies caps each. */
    private final Map<String, Map<String, Cost>> caps = new ConcurrentHashMap<>();
    /** Reservations admitted and not yet finished, by job id: what the fan-out has committed but not priced. */
    private final Map<String, Cost> inFlight = new ConcurrentHashMap<>();
    private final Function<String, ModelSpec> specs;

    public CostLedger() {
        this(Models::findSpec);
    }

    /** Over an explicit catalog lookup, so the arithmetic is testable on a scripted spec. */
    public CostLedger(Function<String, ModelSpec> specs) {
        this.specs = specs;
    }

    /** One call, as priced. */
    public record Call(String jobId, String jobName, String modelId, String servedModelId, long inputTokens,
                       long cacheWriteTokens, long cacheReadTokens, long outputTokens, long latencyMs, Cost cost) {}

    /** One model's share of a workflow: how many calls, how many tokens, how long, how much. */
    public static final class ModelSpend {
        private final String modelId;
        private long calls;
        private long inputTokens;
        private long cacheWriteTokens;
        private long cacheReadTokens;
        private long outputTokens;
        private long latencyMs;
        private Cost cost;
        private long unpricedCalls;

        ModelSpend(String modelId) {
            this.modelId = modelId;
        }

        void add(Call call) {
            calls++;
            inputTokens += call.inputTokens();
            cacheWriteTokens += call.cacheWriteTokens();
            cacheReadTokens += call.cacheReadTokens();
            outputTokens += call.outputTokens();
            latencyMs += call.latencyMs();
            if (call.cost() == null) {
                unpricedCalls++;
            }
            else {
                cost = cost == null ? call.cost() : cost.plus(call.cost());
            }
        }

        public String getModelId() {return modelId;}

        public long getCalls() {return calls;}

        public long getInputTokens() {return inputTokens;}

        public long getCacheWriteTokens() {return cacheWriteTokens;}

        public long getCacheReadTokens() {return cacheReadTokens;}

        public long getOutputTokens() {return outputTokens;}

        public long getLatencyMs() {return latencyMs;}

        /** Null when no priced call ran on this model. */
        public Cost getCost() {return cost;}

        public long getUnpricedCalls() {return unpricedCalls;}
    }

    /** A workflow's spend so far: totals per currency, the split per model, every call. */
    public static final class Workflow {
        private final String workflowId;
        private final Map<String, Cost> totals = new ConcurrentHashMap<>();
        private final Map<String, ModelSpend> byModel = new ConcurrentHashMap<>();
        private final List<Call> calls = new CopyOnWriteArrayList<>();

        Workflow(String workflowId) {
            this.workflowId = workflowId;
        }

        synchronized void add(Call call) {
            calls.add(call);
            byModel.computeIfAbsent(call.modelId() == null ? "(unknown)" : call.modelId(), ModelSpend::new).add(call);
            if (call.cost() != null) {
                totals.merge(call.cost().currency(), call.cost(), Cost::plus);
            }
        }

        public String getWorkflowId() {return workflowId;}

        /** Spent so far, one figure per currency the workflow's calls were priced in. */
        public Map<String, Cost> getTotals() {return Collections.unmodifiableMap(totals);}

        /** Spent so far in one currency; zero when nothing priced in it ran. */
        public Cost getTotal(String currency) {
            Cost total = totals.get(currency);
            return total != null ? total : new Cost(0.0, currency);
        }

        public Map<String, ModelSpend> getByModel() {return Collections.unmodifiableMap(byModel);}

        public List<Call> getCalls() {return Collections.unmodifiableList(calls);}

        public long getUnpricedCalls() {
            long n = 0;
            for (ModelSpend spend : byModel.values()) {
                n += spend.unpricedCalls;
            }
            return n;
        }
    }

    // ==================== recording ====================

    @Override
    public Predicate<JobEvent> getPredicate() {
        return event -> event instanceof AbstractTerminalEvent<?>;
    }

    @Override
    public void observe(JobEvent event) {
        AbstractTerminalEvent<?> terminal = (AbstractTerminalEvent<?>) event;
        JobSnapshot snapshot = terminal.snapshot();
        for (LLMResponse<?> response : terminal.getLlmResponses()) {
            record(snapshot, response);
        }
        // the job is done: what it reserved is no longer committed, what it spent is recorded
        inFlight.keySet().removeIf(key -> key.startsWith(snapshot.getWorkflowId() + "/" + snapshot.getJobId() + "/"));
    }

    private void record(JobSnapshot snapshot, LLMResponse<?> response) {
        String modelId = response.getModel();
        ModelSpec spec = modelId != null ? specs.apply(modelId) : null;
        long input = response.getActualInputTokens() != null ? response.getActualInputTokens() : 0;
        long cacheWrite = response.getCacheCreationInputTokens() != null ? response.getCacheCreationInputTokens() : 0;
        long cacheRead = response.getCacheReadInputTokens() != null ? response.getCacheReadInputTokens() : 0;
        long output = response.getActualOutputTokens() != null ? response.getActualOutputTokens() : 0;
        Cost cost = spec != null ? Cost.of(spec, input, cacheWrite, cacheRead, output) : null;
        if (spec == null) {
            log.warn("Call by {} on model '{}' which the catalog does not know; counted unpriced", snapshot.getJobId(), modelId);
        }
        Call call = new Call(snapshot.getJobId(), snapshot.getDisplayName(), spec != null ? spec.getId() : modelId,
                response.getServedModelId(), input, cacheWrite, cacheRead, output, response.getLatencyMs(), cost);
        workflows.computeIfAbsent(snapshot.getWorkflowId(), Workflow::new).add(call);
    }

    /** The workflow's spend so far, or null when none of its jobs has finished a call. */
    public Workflow workflow(String workflowId) {
        return workflows.get(workflowId);
    }

    /** Spent so far by a workflow in a currency: zero before its first priced call. */
    public Cost spent(String workflowId, String currency) {
        Workflow workflow = workflows.get(workflowId);
        return workflow != null ? workflow.getTotal(currency) : new Cost(0.0, currency);
    }

    // ==================== the cap ====================

    /**
     * Caps what a workflow may commit in one currency from now on: nothing mid-flight
     * changes, new work past the cap is refused. A workflow paying in two currencies holds
     * two caps; a job priced in a currency the workflow holds no cap for is refused, since
     * a cap that said nothing about that money would be no cap.
     */
    public void cap(String workflowId, Cost cap) {
        caps.computeIfAbsent(workflowId, k -> new ConcurrentHashMap<>()).put(cap.currency(), cap);
    }

    /** The workflow's caps by currency; empty when it runs under none. */
    public Map<String, Cost> caps(String workflowId) {
        Map<String, Cost> own = caps.get(workflowId);
        return own != null ? Collections.unmodifiableMap(own) : Map.of();
    }

    public void uncap(String workflowId) {
        caps.remove(workflowId);
    }

    /** What the workflow's admitted, unfinished jobs have committed in a currency. */
    public Cost committed(String workflowId, String currency) {
        Cost sum = new Cost(0.0, currency);
        for (Map.Entry<String, Cost> e : inFlight.entrySet()) {
            if (e.getKey().startsWith(workflowId + "/") && e.getValue().currency().equals(currency)) {
                sum = sum.plus(e.getValue());
            }
        }
        return sum;
    }

    @Override
    public void admit(JobContext<?> context, List<ModelBinding> priced) throws SpendCapExceededException {
        String workflowId = context.getWorkflowId();
        Map<String, Cost> own = caps.get(workflowId);
        if (own == null || own.isEmpty()) {
            return;
        }
        // what this attempt would commit, per currency; one attempt's bindings may be priced in several
        Map<String, Cost> reservations = new LinkedHashMap<>();
        for (ModelBinding binding : priced) {
            ModelSpec model = binding.getModel();
            Cost reserved = Cost.reserved(model, binding.getReservedInput(), binding.getReservedOutput());
            if (reserved == null) {
                throw new SpendCapExceededException("Workflow " + workflowId + " runs under a spend cap and " + context.getJobId()
                        + " would call " + model.getId() + ", which the catalog does not price; a spend nobody can state cannot"
                        + " be admitted under a cap. Price the entry or lift the cap.");
            }
            Cost cap = own.get(reserved.currency());
            if (cap == null) {
                throw new SpendCapExceededException("Workflow " + workflowId + " runs under caps in " + own.keySet() + " and "
                        + context.getJobId() + " would call " + model.getId() + ", priced in " + reserved.currency()
                        + "; the runtime never converts, so add a cap in " + reserved.currency() + " or route the job elsewhere.");
            }
            reservations.merge(reserved.currency(), reserved, Cost::plus);
        }
        for (Cost reservation : reservations.values()) {
            Cost cap = own.get(reservation.currency());
            Cost spent = spent(workflowId, cap.currency());
            Cost committed = committed(workflowId, cap.currency());
            Cost would = spent.plus(committed).plus(reservation);
            if (would.exceeds(cap)) {
                throw new SpendCapExceededException("Workflow " + workflowId + " has spent " + spent + " and committed " + committed
                        + " in flight against its cap of " + cap + ", and " + context.getJobId() + " would reserve up to "
                        + reservation + " more. Refused at admission; nothing running was stopped. Raise the cap to continue.");
            }
        }
        for (Cost reservation : reservations.values()) {
            inFlight.put(workflowId + "/" + context.getJobId() + "/" + reservation.currency(), reservation);
        }
    }
}
