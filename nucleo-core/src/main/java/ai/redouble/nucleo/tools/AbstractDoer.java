/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.tools.thinking.*;

import java.util.*;

/**
 * Base class for coded orchestration logic.
 *
 * <p>Doers are coordinators for coded logic. They can be invoked by Thinkers
 * just like any other tool, but their execute() contains coded logic that spawns
 * and coordinates other jobs dynamically.
 *
 * <p><b>Execute contract:</b> Write natural Java code. Spawn sequential jobs with
 * {@link #submitInStep}, fan out with {@link #nextStep} + {@link #submitInCurrentStep},
 * call {@code handle.get()} to wait. Branch, loop, whatever. Return when done. The
 * helpers stamp each child with the doer's step ordinal (see {@code stepOrdinal}); a raw
 * {@code JobDispatcher.submit} leaves the child unstamped and breaks execution-order
 * reconstruction.
 *
 * <p><b>Key differences from simple tools:</b>
 * <ul>
 *   <li>Doers hold no resources - they orchestrate other jobs that hold resources</li>
 *   <li>Doers can run for hours/days - each sub-job acquires and releases resources independently</li>
 *   <li>Doers enable dynamic workflow expansion - spawn N jobs based on runtime data</li>
 * </ul>
 *
 * <p><b>Use cases:</b>
 * <ul>
 *   <li>Fan-out/fan-in: spawn N workers dynamically based on data, wait for all, aggregate</li>
 *   <li>Conditional workflows: run job A, based on result decide whether to run B or C</li>
 *   <li>Retry orchestration: coded retry logic with backoff</li>
 *   <li>Multi-stage pipelines: each stage determines next stage's parallelism</li>
 * </ul>
 *
 * <p><b>Example:</b>
 * <pre>
 * &#64;ToolName("process_invoices")
 * &#64;ToolDescription("Processes all pending invoices in parallel")
 * public class InvoiceProcessingDoer extends AbstractDoer&lt;InvoiceInput, InvoiceResult&gt; {
 *
 *     public InvoiceProcessingDoer(Identifiable parent) {
 *         super(parent);
 *     }
 *
 *     &#64;Override
 *     public InvoiceResult execute(JobContext&lt;InvoiceResult&gt; context) throws Exception {
 *         // Step 1: get the pending invoices
 *         JobHandle&lt;List&lt;Invoice&gt;&gt; listHandle = submitInStep(new ListPendingInvoicesJob(this));
 *         List&lt;Invoice&gt; invoices = listHandle.get();
 *
 *         // Step 2: fan out - one job per invoice, all sharing one step ordinal
 *         nextStep();
 *         List&lt;JobHandle&lt;ProcessedInvoice&gt;&gt; handles = new ArrayList&lt;&gt;();
 *         for (Invoice invoice : invoices) {
 *             ProcessInvoiceJob job = new ProcessInvoiceJob(this);
 *             job.setInput(invoice);
 *             handles.add(submitInCurrentStep(job));
 *         }
 *
 *         // Fan in - collect results
 *         List&lt;ProcessedInvoice&gt; results = new ArrayList&lt;&gt;();
 *         for (JobHandle&lt;ProcessedInvoice&gt; handle : handles) {
 *             results.add(handle.get());
 *         }
 *
 *         return new InvoiceResult(results);
 *     }
 * }
 * </pre>
 *
 * @param <I> Input type - a POJO for structured input
 * @param <O> Output type - a POJO for structured output
 * @see Orchestrator
 * @see AbstractThinker
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-12-02)
 */
public abstract class AbstractDoer<I , O > extends AbstractOrchestrator<I, O> implements Doer<I, O> {

    /**
     * Per-doer monotonic counter, stamped onto every child job submitted via
     * {@link #submitInStep} / {@link #submitInCurrentStep} and persisted by the
     * deployment's recorder as the child's iteration number. The contract for coded doers is:
     * <ul>
     *   <li><b>Sequential step</b>: each call to {@link #submitInStep} bumps
     *       the counter, so jobs run back-to-back receive ordinals 1, 2, 3...
     *       and sorting direct children by {@code iteration_number} reproduces
     *       execution order.</li>
     *   <li><b>Fan-out</b>: call {@link #nextStep} once, then
     *       {@link #submitInCurrentStep} for every parallel job. All jobs in
     *       the fan-out share one ordinal so the UI can group them as a single
     *       step.</li>
     * </ul>
     * Doers that bypass these helpers and call {@code JobDispatcher.submit}
     * directly leave the row with a null {@code iteration_number}, which
     * breaks ordering.
     */
    private long stepOrdinal = 0L;

    /**
     * Creates a doer with lineage tracking.
     *
     * @param parent the parent identity for lineage tracking
     */
    public AbstractDoer(Identifiable parent) {
        super(parent);
    }

    /**
     * Creates a doer with lineage tracking and custom ID prefix.
     *
     * @param parent the parent identity for lineage tracking
     * @param idPrefix custom prefix for the doer's job ID
     */
    public AbstractDoer(Identifiable parent, String idPrefix) {
        super(parent, idPrefix);
    }

    /**
     * Bumps and returns the doer's step counter. Use when starting a new
     * fan-out: call this once, then submit every parallel job through
     * {@link #submitInCurrentStep} so they all share the same ordinal.
     */
    protected long nextStep() {
        return ++stepOrdinal;
    }

    /**
     * Submits a single sequential job: bumps the step counter and stamps
     * the new ordinal onto the child. Use this for back-to-back work where
     * each {@code submitInStep} call corresponds to one logical step.
     */
    protected <T > JobHandle<T> submitInStep(Job<T> job) {
        nextStep();
        return submitInIteration(stepOrdinal, job);
    }

    /**
     * Submits a job using the current step ordinal without bumping. Use
     * inside a fan-out: call {@link #nextStep} once to claim an ordinal,
     * then call this for every parallel job in the fan-out.
     */
    protected <T > JobHandle<T> submitInCurrentStep(Job<T> job) {
        return submitInIteration(stepOrdinal, job);
    }

    /**
     * Submits a job in the current step carrying the doer's own metadata entries, seeded
     * into the child's context before it is scheduled so every event the child emits
     * carries them: a doer racing many jobs names each one for an observer this way.
     */
    protected <T> JobHandle<T> submitInCurrentStep(Job<T> job, Map<String, Object> metadata) {
        return submitInIteration(stepOrdinal, job, metadata);
    }

    /**
     * Submits a dependency-sequenced job using the current step ordinal: the scheduler
     * holds it until every dependency completes, and the job reads their results via
     * {@code JobContext.getDependencyResults()}. Use inside a fan-out whose stages form
     * a DAG - submit everything, let the scheduler order it.
     */
    protected <T> JobHandle<T> submitInCurrentStep(Job<T> job, List<JobHandle<?>> dependencies) {
        return submitInIteration(stepOrdinal, job, dependencies);
    }
}
