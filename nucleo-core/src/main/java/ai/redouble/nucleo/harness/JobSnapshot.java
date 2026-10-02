/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import com.fasterxml.jackson.annotation.*;

import java.io.*;
import java.time.*;
import java.util.*;

/**
 * Immutable snapshot of a job's identity and state at a specific point in time.
 *
 * <p>Captures job identity (jobId, userId, workflowId), human-readable metadata
 * (displayName from {@code @DisplayName}, description from {@code @ToolDescription}),
 * and state (timestamps, attemptNumber) frozen at a moment in time.
 * Instances are immutable; use {@code with*} methods to create new snapshots.
 *
 * <p>The {@code description} field is extracted reflectively from {@code @ToolDescription}
 * to avoid a compile-time dependency from Nucleo to the tools layer. Use
 * {@link #withDescription(String)} to override with a runtime value (e.g., from a
 * prompt management system).
 *
 * <p><b>JSON Serialization:</b>
 * Included in {@link JobEvent} JSON as nested {@code snapshot} object.
 * Fields {@code jobClass}, {@code state}, {@code description}, {@code dependencyJobIds}
 * and {@code metadata} excluded via {@code @JsonIgnore}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-10-08)
 */
public class JobSnapshot implements Serializable, Identifiable {
    private final String jobId;
    private final String parentJobId;
    private final String workflowId;
    private final String userId;
    @JsonIgnore
    private final Class<? extends Job> jobClass;
    private final JobType jobType;
    private final String displayName;
    private final String action;
    @JsonIgnore
    private final String description;
    private final JobState state;
    private final int attemptNumber;
    private final Instant submittedAt;
    private final Instant startedAt;
    private final Instant completedAt;
    @JsonIgnore
    private final List<String> dependencyJobIds;
    @JsonIgnore
    private final Map<String, Object> metadata;

    /**
     * Creates a new snapshot from a Job with QUEUED state.
     * Prefers {@link Job#getDisplayName()} if non-null, otherwise falls back to annotation/class name.
     */
    public JobSnapshot(Job<?> job) {
        this(job.getJobId(), job.getParentJobId(), job.getWorkflowId(), job.getUserId(), job.getClass(), job.getJobType(), resolveDisplayName(job), extractAction(job.getClass()), extractDescription(job.getClass()), JobState.QUEUED, 0, Instant.now(), null, null, new ArrayList<>(), Map.of());
    }

    /**
     * Full constructor for all fields.
     */
    public JobSnapshot(String jobId, String parentJobId, String workflowId, String userId, Class<? extends Job> jobClass, JobType jobType, String displayName, String action, String description, JobState state, int attemptNumber, Instant submittedAt, Instant startedAt, Instant completedAt, List<String> dependencyJobIds, Map<String, Object> metadata) {
        this.jobId = jobId;
        this.parentJobId = parentJobId;
        this.workflowId = workflowId;
        this.userId = userId;
        this.jobClass = jobClass;
        this.jobType = jobType;
        this.displayName = displayName;
        this.action = action;
        this.description = description;
        this.state = state;
        this.attemptNumber = attemptNumber;
        this.submittedAt = submittedAt;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.dependencyJobIds = dependencyJobIds != null ? Collections.unmodifiableList(new ArrayList<>(dependencyJobIds)) : Collections.emptyList();
        this.metadata = metadata;
    }

    /**
     * Resolves display name: instance override first, then annotation, then class simple name.
     */
    private static String resolveDisplayName(Job<?> job) {
        String instanceName = job.getDisplayName();
        if (instanceName != null) return instanceName;
        return extractDisplayName(job.getClass());
    }

    /**
     * Extracts human-readable name from @DisplayName annotation or falls back to class simple name.
     */
    public static String extractDisplayName(Class<? extends Job> jobClass) {
        if (jobClass == null) {
            return null;
        }
        DisplayName annotation = jobClass.getAnnotation(DisplayName.class);
        if (annotation != null) {
            return annotation.value();
        }
        return jobClass.getSimpleName();
    }

    /**
     * Extracts action verb from @DisplayName annotation.
     * Returns empty string if not specified.
     */
    public static String extractAction(Class<? extends Job> jobClass) {
        if (jobClass == null) {
            return "";
        }
        DisplayName annotation = jobClass.getAnnotation(DisplayName.class);
        if (annotation != null && !annotation.action().isEmpty()) {
            return annotation.action();
        }
        return "";
    }

    /**
     * Extracts description from @ToolDescription annotation via reflection.
     * Uses reflective annotation lookup to avoid a dependency from Nucleo to the tools layer.
     * Returns null if the annotation is not present or the class is not on the classpath.
     */
    public static String extractDescription(Class<? extends Job> jobClass) {
        if (jobClass == null) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Class<? extends java.lang.annotation.Annotation> annClass =
                    (Class<? extends java.lang.annotation.Annotation>) Class.forName("ai.redouble.nucleo.tools.ToolDescription");
            java.lang.annotation.Annotation annotation = jobClass.getAnnotation(annClass);
            if (annotation != null) {
                return (String) annClass.getMethod("value").invoke(annotation);
            }
        }
        catch (ClassNotFoundException ignored) {
            // ToolDescription not on classpath - Nucleo used standalone
        }
        catch (Exception e) {
            // Reflection failure - not critical for observability
        }
        return null;
    }

    // Getters for JobSnapshot fields
    @Override
    public String getJobId() {
        return jobId;
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

    public Class<? extends Job> jobClass() {
        return jobClass;
    }

    public JobType getJobType() {
        return jobType;
    }

    public String getAction() {
        return action;
    }

    public String getDescription() {
        return description;
    }

    @JsonIgnore
    public JobState getState() {
        return state;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public List<String> dependencyJobIds() {
        return dependencyJobIds;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    // JavaBean-style getters for Jackson JSON serialization
    public String getDisplayName() {
        return displayName;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    /**
     * Returns a new snapshot with updated state.
     */
    public JobSnapshot withState(JobState newState) {
        return new JobSnapshot(jobId, parentJobId, workflowId, userId, jobClass, jobType, displayName, action, description, newState, attemptNumber, submittedAt, startedAt, completedAt, dependencyJobIds, metadata);
    }

    /**
     * Returns a new snapshot with updated attempt number.
     */
    public JobSnapshot withAttemptNumber(int attemptNumber) {
        return new JobSnapshot(jobId, parentJobId, workflowId, userId, jobClass, jobType, displayName, action, description, state, attemptNumber, submittedAt, startedAt, completedAt, dependencyJobIds, metadata);
    }

    /**
     * Returns a new snapshot with started timestamp.
     */
    public JobSnapshot withStartedAt(Instant startedAt) {
        return new JobSnapshot(jobId, parentJobId, workflowId, userId, jobClass, jobType, displayName, action, description, state, attemptNumber, submittedAt, startedAt, completedAt, dependencyJobIds, metadata);
    }

    /**
     * Returns a new snapshot with completed timestamp.
     */
    public JobSnapshot withCompletedAt(Instant completedAt) {
        return new JobSnapshot(jobId, parentJobId, workflowId, userId, jobClass, jobType, displayName, action, description, state, attemptNumber, submittedAt, startedAt, completedAt, dependencyJobIds, metadata);
    }

    /**
     * Returns a new snapshot with dependency job IDs.
     */
    public JobSnapshot withDependencyJobIds(List<String> dependencyJobIds) {
        return new JobSnapshot(jobId, parentJobId, workflowId, userId, jobClass, jobType, displayName, action, description, state, attemptNumber, submittedAt, startedAt, completedAt, dependencyJobIds, metadata);
    }

    /**
     * Returns a new snapshot with overridden description.
     * Use when description comes from a runtime source (e.g., prompt management) rather than annotation.
     */
    public JobSnapshot withDescription(String description) {
        return new JobSnapshot(jobId, parentJobId, workflowId, userId, jobClass, jobType, displayName, action, description, state, attemptNumber, submittedAt, startedAt, completedAt, dependencyJobIds, metadata);
    }

    /**
     * Returns a new snapshot with updated metadata.
     */
    public JobSnapshot withMetadata(Map<String, Object> metadata) {
        return new JobSnapshot(jobId, parentJobId, workflowId, userId, jobClass, jobType, displayName, action, description, state, attemptNumber, submittedAt, startedAt, completedAt, dependencyJobIds, metadata);
    }

    /**
     * Gets the job type name (simple class name).
     */
    public String jobType() {
        return jobClass != null ? jobClass.getSimpleName() : null;
    }
}