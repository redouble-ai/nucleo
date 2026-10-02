/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import java.util.*;

/**
 * Workflow identity for root jobs that start new workflow trees.
 * Created via {@link Job#workflow(String, String)} factory method.
 *
 * <p>Workflows have no parent and their workflowId equals their jobId.
 * IDs use format: {@code prefix-uuid} (e.g., "doc-ingest-419c-9663-97a6df10d9a0").</p>
 *
 * @param workflowId unique workflow identifier
 * @param userId user who owns this workflow
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-10-08)
 */
record Workflow(String workflowId, String userId) implements Identifiable {
    /**
     * Creates a workflow identity with readable ID.
     *
     * @param userId user who owns workflow (required)
     * @param workflowPrefix readable prefix (required)
     * @return new workflow identity
     * @throws IllegalArgumentException if userId or workflowPrefix is null/empty
     */
    static Workflow create(String userId, String workflowPrefix) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("userId is required");
        }
        if (workflowPrefix == null || workflowPrefix.isEmpty()) {
            throw new IllegalArgumentException("workflowPrefix is required");
        }
        String uuid = UUID.randomUUID().toString();
        String suffix = uuid.substring(uuid.indexOf('-', 14) + 1);
        return new Workflow(workflowPrefix + "-" + suffix, userId);
    }

    @Override
    public String getJobId() {
        return workflowId;  // For root jobs, jobId == workflowId
    }

    @Override
    public String getParentJobId() {
        return null;  // Root jobs have no parent
    }

    @Override
    public String getWorkflowId() {
        return workflowId;
    }

    @Override
    public String getUserId() {
        return userId;
    }
}
