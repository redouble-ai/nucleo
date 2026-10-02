/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

/**
 * Classification of job types for analytics and cost attribution.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-12-02)
 */
public enum JobType {
    /**
     * LLM-driven orchestrators: a thinker that reasons with a model and calls tools.
     */
    THINKER,
    /**
     * Coded orchestrators: a doer that sequences and fans out children without a model.
     */
    DOER,
    /**
     * Single operations with typed input and output: a tool.
     */
    TOOL,
    /**
     * Validation gates run on another job's behalf: a guardrail.
     */
    GUARDRAIL,
    /**
     * One exchange with a model on a thinker's behalf.
     */
    LLM_CALL,
    /**
     * Any other job; the default of {@link Job#getJobType()}.
     */
    JOB,
    /**
     * Housekeeping within a workflow: compaction, enrichment, lookups.
     */
    UTILITY
}
