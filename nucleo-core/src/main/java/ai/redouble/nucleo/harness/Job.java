/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

/**
 * Interface for all jobs that can be submitted to the job processing system.
 * Jobs are units of work that declare their resource requirements and execute
 * with provided resources.
 *
 * @param <T> the type of result this job produces
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-08-11)
 */
public interface Job<T> extends Identifiable {
    /**
     * Creates a workflow identity for root jobs.
     *
     * @param userId         user who owns the workflow
     * @param workflowPrefix readable prefix (e.g., "doc-ingest", "query-analysis")
     * @return workflow identity for job constructors
     * @throws IllegalArgumentException if userId or workflowPrefix is null/empty
     */
    static Identifiable workflow(String userId, String workflowPrefix) {
        return Workflow.create(userId, workflowPrefix);
    }



    /**
     * Gets the unique job ID.
     * Implements {@link Identifiable#getJobId()}.
     *
     * @return the job ID
     */
    String getId();

    /**
     * Gets the unique job ID (alias for getId()).
     * From {@link Identifiable} interface.
     *
     * @return the job ID
     */
    @Override
    default String getJobId() {
        return getId();
    }

    /**
     * Gets the job priority (higher values = higher priority).
     *
     * @return the priority
     */
    int getPriority();

    /**
     * Gets the user-friendly name for this job.
     *
     * @return the job name
     */
    String getName();

    /**
     * Declares resource requirements for this job.
     * Returns null for jobs that need no resources (e.g., orchestrators).
     *
     * @return resource requirements, or null if the job needs no resources
     */
    JobRequirements getRequirements();

    /**
     * Returns a runtime-computed display name for this job instance, or null to use
     * the {@code @DisplayName} annotation (or class simple name as fallback).
     *
     * <p>Override when the display name depends on instance state. For example,
     * a sub-agent can return "Sub-Agent of (parent name)" without hardcoding
     * that logic in the framework.
     *
     * @return instance-specific display name, or null for annotation-based default
     */
    default String getDisplayName() {
        return null;
    }

    /**
     * Gets the job type classification for analytics and cost attribution.
     * Override in subclasses that need a specific type (TOOL, THINKER, DOER, etc.).
     *
     * @return the job type (default is JOB)
     */
    default JobType getJobType() {
        return JobType.JOB;
    }

    /**
     * Maximum time before the dispatcher times out and kills this job. Null means no deadline -
     * the dispatcher schedules no killer (used by orchestrators, which only wait on tools).
     *
     * @return the timeout duration, or null for no timeout (default 30 minutes)
     */
    default java.time.Duration getTimeout() {
        return java.time.Duration.ofMinutes(30);
    }

    /**
     * The transparent re-runs a job gets by default when the upstream answers with a retry
     * signal. Three: the pre-emptive limiters keep a 429 rare and one re-run usually clears
     * it, and a fault still there on the third attempt is an outage or a request the
     * endpoint cannot serve, which more waiting never fixes.
     */
    int DEFAULT_UPSTREAM_RETRIES = 3;

    /**
     * How many transparent re-runs the dispatcher may give this job when the upstream
     * fails with a retry signal (a 429, a 529, a plain 5xx or a connection failure), on
     * one shared counter per execution; past them the job fails with the last signal as
     * cause. The pacing window scales by attempt, so every re-run is minutes a caller
     * waits behind: a job whose caller waits interactively sets fewer, an unattended
     * pipeline may set more.
     *
     * @return the budget (default {@link #DEFAULT_UPSTREAM_RETRIES})
     */
    default int getUpstreamRetries() {
        return DEFAULT_UPSTREAM_RETRIES;
    }

    /**
     * Executes the job with the provided resources and context. A job reports progress and
     * checks for cancellation through the context, and completes all its work before
     * returning. A tool narrows this contract to the LLM-readable exceptions of
     * {@link ai.redouble.nucleo.tools.Tool#execute}.
     *
     * @param resources the allocated resources
     * @param context   the job execution context for monitoring
     * @return the job result, which may be null
     * @throws Exception                    if the job fails
     * @throws JobContext.CancellationException if the job is cancelled
     */
    T execute(JobResources resources, JobContext<T> context) throws Exception;


    /**
     * Hook called once per execution attempt, after resources are allocated and before the
     * started event and {@link #execute}. Use for pre-execution setup that needs the job
     * context (e.g., stashing input for observability). Do NOT use for work that requires
     * resources - that belongs in execute().
     *
     * @param context the job context
     */
    default void preExecute(JobContext<T> context) {
        // Default implementation does nothing
    }

    /**
     * Hook called after execution completes (success or failure), after resources are released,
     * but before the terminal event is published. Use for post-execution bookkeeping that
     * needs to land in the terminal event's metadata (e.g., stashing output for observability).
     * On success, the result is available via context.getResult().
     *
     * @param context the job context
     */
    default void postExecute(JobContext<T> context) {
        // Default implementation does nothing
    }
}