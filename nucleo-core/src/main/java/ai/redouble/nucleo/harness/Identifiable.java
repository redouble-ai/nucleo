/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

/**
 * Interface for workflow lineage tracking.
 * All jobs, contexts, snapshots, and handles implement this interface.
 *
 * <h2>Lineage Fields:</h2>
 * <ul>
 *   <li><b>workflowId</b>: Identifies entire workflow tree (root job's ID)</li>
 *   <li><b>parentJobId</b>: Immediate parent (null for root)</li>
 *   <li><b>jobId</b>: Unique job identifier</li>
 *   <li><b>userId</b>: Workflow owner</li>
 * </ul>
 *
 * <h2>Usage:</h2>
 * <pre>{@code
 * // Root job
 * Identifiable root = Job.workflow(userId, "doc-ingest");
 * MyJob job = new MyJob(root);
 *
 * // Child job
 * MyChildJob child = new MyChildJob(context);
 * }</pre>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-10-08)
 */
public interface Identifiable {

    /**
     * Gets unique job identifier.
     *
     * @return job ID
     */
    String getJobId();

    /**
     * Gets parent job ID.
     *
     * @return parent job ID, or null for root
     */
    String getParentJobId();

    /**
     * Gets workflow identifier (root job's ID).
     *
     * @return workflow ID
     */
    String getWorkflowId();

    /**
     * Gets workflow owner.
     *
     * @return user ID
     */
    String getUserId();
}
