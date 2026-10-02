/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import java.time.*;
import java.util.*;

/**
 * Base class for all jobs that can be submitted to the job processing system.
 * Jobs are units of work that declare their resource requirements and execute
 * with provided resources. The constructor mints the job's identity (a readable prefix, the
 * class simple name when none is given, plus a random tail) and inherits its lineage from the
 * parent it is handed; a root job's parent is {@link Job#workflow(String, String)}.
 * <p>
 * IMPORTANT: Jobs must be stateless or idempotent!
 * The same Job instance is executed again when the dispatcher re-runs an attempt (an
 * upstream throttle, a truncated answer, a correction).
 * Do not rely on mutable instance fields that change during execution.
 * Instead, use immutable fields set in the constructor or fetch state from
 * the database on each execution.
 * <p>
 * Good practice:
 * - Store IDs in final fields
 * - Fetch entities from database in execute()
 * - Don't accumulate state in instance variables
 *
 * @param <T> the type of result this job produces
 * @author Andrey Santrosyan
 * @since 0.1 (2025-08-10)
 */
public abstract class AbstractJob<T> implements Job<T> {

    /**
     * Unique identifier for this job instance: the prefix given at construction, or the class
     * simple name, and a random tail.
     */
    private final String jobId;

    /**
     * Priority of this job (higher values = higher priority).
     */
    private final int priority;

    /**
     * Parent job ID if this job was spawned by another job.
     * Null for root jobs.
     */
    private final String parentJobId;

    /**
     * Workflow ID that groups all jobs in the same workflow tree.
     * Set to the root job's ID for the entire tree.
     */
    private final String workflowId;

    /**
     * User who owns this workflow.
     */
    private final String userId;

    /**
     * User-friendly name for this job.
     */
    private final String name;

    /**
     * Maximum time before the dispatcher times out and kills this job.
     * Subclasses set this in their constructor via {@link #setTimeout(Duration)}.
     */
    protected Duration timeout = Duration.ofMinutes(30);

    /**
     * How many transparent re-runs the dispatcher may give this job on an upstream retry
     * signal; see {@link Job#getUpstreamRetries()}. Set per job through
     * {@link #setUpstreamRetries(int)}, by the job's constructor or by its caller.
     */
    protected int upstreamRetries = DEFAULT_UPSTREAM_RETRIES;

    /**
     * Primary constructor for lineage-aware jobs.
     * All jobs must provide an Identifiable parent for lineage tracking.
     * Use {@link Job#workflow(String, String)} for root jobs.
     *
     * @param parent   the parent identity (use Job.workflow() for root jobs)
     * @param idPrefix prefix for the job ID, or null to use class name
     * @throws IllegalArgumentException if parent is null
     */
    protected AbstractJob(Identifiable parent, String idPrefix) {
        if (parent == null) {
            throw new IllegalArgumentException("Parent identity required. Use Job.workflow(userId, workflowPrefix) for root jobs.");
        }
        // Generate unique ID with UUID suffix for better uniqueness
        // e.g., "DocumentIngestJob-a26f-52b01fa3bd75"
        String suffix = randomIdSuffix();
        if (idPrefix != null) {
            this.jobId = idPrefix + "-" + suffix;
        }
        else {
            this.jobId = getClass().getSimpleName() + "-" + suffix;
        }
        this.priority = 0;
        this.name = getClass().getSimpleName() + "-" + this.jobId;

        // Inherit lineage from parent
        this.parentJobId = parent.getJobId();
        this.workflowId = parent.getWorkflowId();
        this.userId = parent.getUserId();
    }

    /**
     * The framework's readable-id tail: the last two segments of a random UUID
     * (e.g. {@code a26f-52b01fa3bd75}). Prefixed with a human-readable name it makes
     * every framework identity - job ids here, minted conversation ids in thinkers -
     * scannable in logs and DB rows while staying unique enough for their lifetimes.
     */
    protected static String randomIdSuffix() {
        String uuid = UUID.randomUUID().toString();
        return uuid.substring(uuid.indexOf('-', 14) + 1);
    }

    /**
     * Gets the unique job ID.
     * This is final to prevent subclasses from overriding.
     *
     * @return the job ID
     */
    public final String getId() {
        return jobId;
    }

    @Override
    public Duration getTimeout() {
        return timeout;
    }

    protected void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    @Override
    public int getUpstreamRetries() {
        return upstreamRetries;
    }

    /** The transparent re-runs this job may claim on an upstream retry signal; see {@link Job#getUpstreamRetries()}. */
    public void setUpstreamRetries(int upstreamRetries) {
        this.upstreamRetries = upstreamRetries;
    }

    public int getPriority() {
        return priority;
    }

    @Override
    public String getParentJobId() {
        return parentJobId;
    }

    @Override
    public String getWorkflowId() {
        return workflowId;
    }

    @Override
    public String getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return String.format("%s[id=%s, name=%s, priority=%d]", getClass().getSimpleName(), jobId, name, priority);
    }
}