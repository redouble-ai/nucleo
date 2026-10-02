/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools;

import ai.redouble.nucleo.guardrails.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.tools.thinking.*;

import java.time.*;
import java.util.*;

/**
 * Base implementation for resource-free orchestrators that coordinate other jobs.
 *
 * <p>Coordinators hold no database connections or LLM tokens. They only hold job handles.
 * This allows them to wait for hours/days without consuming system resources (just a virtual thread).
 *
 * <p><b>Resource-Free Design:</b>
 * <ul>
 *   <li>No database connections - cannot query or write directly</li>
 *   <li>No LLM tokens - cannot call LLMs directly</li>
 *   <li>No HTTP connections - cannot make API calls directly</li>
 * </ul>
 *
 * <p>To perform work, coordinators spawn jobs through the iteration-stamping helpers
 * ({@link #submitInIteration} here, {@code submitInStep}/{@code submitInCurrentStep} on a
 * doer) and wait on the returned {@link JobHandle}. This pattern enables:
 * <ul>
 *   <li>Long-running workflows without resource exhaustion</li>
 *   <li>Dynamic workflow expansion (spawn jobs based on runtime data)</li>
 *   <li>Deferral for human-in-the-loop scenarios</li>
 * </ul>
 *
 * <p><b>Subclasses:</b>
 * <ul>
 *   <li>{@link AbstractThinker} - LLM-driven decision loop with tool orchestration</li>
 *   <li>{@link AbstractDoer} - Coded logic orchestration (spawn/wait/return freely)</li>
 * </ul>
 *
 * @param <I> Input type - a POJO for structured input
 * @param <O> Output type - a POJO for structured output
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-12-23)
 */
public abstract class AbstractOrchestrator<I , O > extends AbstractTool<I, O> implements Orchestrator<I, O> {
    private ScopeGuard scopeGuard;
    private volatile boolean scopeGuardSealed;

    /**
     * The authoring surface before dispatch, the sealed effective guard after -
     * {@link ScopeAuthority#getScopeGuard()}. Post-seal this is the captured effective
     * guard (own field + own scope + inherited), so explicit delegation always copies
     * the real thing.
     */
    @Override
    public ScopeGuard getScopeGuard() {
        return scopeGuard;
    }

    @Override
    public void setScopeGuard(ScopeGuard guard) {
        if (scopeGuardSealed) {
            throw new IllegalStateException(getClass().getSimpleName()
                    + ": the scope guard is sealed at dispatch and cannot change for this job's lifetime");
        }
        this.scopeGuard = guard;
    }

    @Override
    public void sealScopeGuard(ScopeGuard effective) {
        this.scopeGuard = effective;
        this.scopeGuardSealed = true;
    }

    /**
     * Orchestrators hold no resources and only wait on tools, each of which carries its own
     * timeout. An orchestrator deadline could fire only while waiting on a healthy tool, so
     * orchestrators have none; null means "no deadline". Final - no doer or thinker may set one.
     */
    @Override
    public final Duration getTimeout() {
        return null;
    }

    @Override
    protected final void setTimeout(Duration timeout) {
        throw new UnsupportedOperationException(
                "Orchestrators have no execution timeout - they hold no resources and only wait on "
                + "tools, which self-protect. Put the timeout on the leaf tool instead.");
    }

    /**
     * Metadata key used to stamp a child job with the orchestrator's current loop iteration.
     * A deployment's recorder lifts it into its persisted job rows so siblings spawned
     * within the same loop tick group together; the runtime itself writes only the metadata.
     *
     * <p>Value type in the metadata map is always {@link Long} - use
     * {@link #submitInIteration(long, Job)} or explicitly box to Long at the call site.
     */
    public static final String META_ITERATION = "iteration";

    /**
     * Creates a coordinator with lineage tracking.
     *
     * @param parent the parent identity for lineage tracking
     */
    public AbstractOrchestrator(Identifiable parent) {
        super(parent);
    }

    /**
     * Creates a coordinator with lineage tracking and custom ID prefix.
     *
     * @param parent   the parent identity for lineage tracking
     * @param idPrefix custom prefix for the coordinator's job ID
     */
    public AbstractOrchestrator(Identifiable parent, String idPrefix) {
        super(parent, idPrefix);
    }

    // ================ Job Configuration ================

    @Override
    public JobType getJobType() {
        return this instanceof Thinker ? JobType.THINKER : JobType.DOER;
    }

    /**
     * Coordinators require NO resources - they coordinate other jobs.
     * Returns null because orchestrators hold no database, LLM, or HTTP resources.
     */
    @Override
    public final JobRequirements getRequirements() {
        return null;
    }

    /**
     * Bridge method - coordinators don't use resources directly.
     * All work is done by spawning other jobs.
     */
    @Override
    public final O execute(JobResources resources, JobContext<O> context) throws LLMReadableCheckedException {
        return this.execute(context);
    }

    /**
     * Main execution - spawn jobs, wait on handles, return when done.
     *
     * <p>Write natural Java code. Spawn children through the iteration-stamping helpers
     * ({@link #submitInIteration}, or a doer's step helpers), call {@code handle.get()} to
     * wait. Branch, loop, whatever. Return when done.
     *
     * @param context the job context for progress reporting and cancellation
     * @return the result of the coordinator's work
     * @throws LLMReadableCheckedException if coordination fails
     */
    public abstract O execute(JobContext<O> context) throws LLMReadableCheckedException;

    /**
     * Submits a child job stamped with the given loop iteration.
     * The iteration is seeded into the child's JobContext metadata under
     * {@link #META_ITERATION} via the dispatcher's opaque metadata overload, for a
     * deployment's recorder to persist.
     *
     * @param <R>       the child job's return type
     * @param iteration the orchestrator's current loop iteration (a thinker's
     *                  agentic tick; a doer's step ordinal, monotonic across
     *                  the doer's entire lifetime)
     * @param job       the child job to submit
     * @return a handle to the submitted child
     */
    protected <R> JobHandle<R> submitInIteration(long iteration, Job<R> job) {
        return JobDispatcher.getInstance().submit(job, Map.of(META_ITERATION, iteration));
    }

    /**
     * Submits a child job stamped with the given loop iteration and carrying the caller's
     * own metadata beside it, seeded into the child's context before it is scheduled, so
     * every event the child emits - scheduling, start, progress, retries, its terminal
     * event - carries those entries on its snapshot for an observer to read.
     *
     * @param metadata the caller's entries; {@link #META_ITERATION} is added to them
     */
    protected <R> JobHandle<R> submitInIteration(long iteration, Job<R> job, Map<String, Object> metadata) {
        Map<String, Object> stamped = new LinkedHashMap<>(metadata);
        stamped.put(META_ITERATION, iteration);
        return JobDispatcher.getInstance().submit(job, stamped);
    }

    /**
     * Submits a dependency-sequenced child in the given iteration: the scheduler holds
     * it until every dependency completes, and the child reads their results through
     * {@code JobContext.getDependencyResults()}. Same bookkeeping stamp as
     * {@link #submitInIteration(long, Job)}.
     */
    protected <R> JobHandle<R> submitInIteration(long iteration, Job<R> job, List<JobHandle<?>> dependencies) {
        return JobDispatcher.getInstance().submit(job, Map.<String, Object>of(META_ITERATION, iteration), dependencies);
    }
}
