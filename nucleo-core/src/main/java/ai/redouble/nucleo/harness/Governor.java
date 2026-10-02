/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import ai.redouble.nucleo.harness.observability.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Public API for the Nucleo job execution engine.
 * Named after James Watt's centrifugal governor - the first automatic resource control mechanism.
 * Controls who gets what resources and when, prevents over-revving.
 * <p>
 * All methods delegate to the internal {@link JobDispatcher} singleton.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-03-25)
 */
public final class Governor {
    private Governor() {}

    // --- Job submission ---

    /**
     * Submits a job for execution.
     *
     * @param job the job to execute
     * @param <T> the result type
     * @return a handle for monitoring and retrieving the result
     */
    public static <T> JobHandle<T> submit(Job<T> job) {
        return JobDispatcher.INSTANCE.submit(job);
    }

    /**
     * Submits a job with a single dependency.
     *
     * @param job the job to execute
     * @param dependency the job that must complete first
     * @param <T> the result type
     * @param <D> the dependency result type
     * @return a handle for monitoring and retrieving the result
     */
    public static <T, D> JobHandle<T> submit(Job<T> job, JobHandle<D> dependency) {
        return JobDispatcher.INSTANCE.submit(job, dependency);
    }

    /**
     * Submits a job with multiple dependencies.
     *
     * @param job the job to execute
     * @param dependencies the jobs that must complete first
     * @param <T> the result type
     * @param <D> the dependency result type
     * @return a handle for monitoring and retrieving the result
     */
    public static <T, D> JobHandle<T> submit(Job<T> job, List<JobHandle<D>> dependencies) {
        return JobDispatcher.INSTANCE.submit(job, dependencies);
    }

    /**
     * Submits a job with a delay before execution.
     *
     * @param job the job to execute
     * @param delay the delay before execution
     * @param <T> the result type
     * @return a future that completes with the job handle when the job is submitted
     */
    public static <T> CompletableFuture<JobHandle<T>> submitWithDelay(Job<T> job, Duration delay) {
        return JobDispatcher.INSTANCE.submitWithDelay(job, delay);
    }

    // --- Lifecycle ---

    /**
     * Starts the Governor and its execution infrastructure.
     */
    public static void start() {
        JobDispatcher.INSTANCE.start();
    }

    /**
     * Shuts down the Governor gracefully.
     *
     * @param timeoutMs maximum time to wait for running jobs to complete
     */
    public static void shutdown(long timeoutMs) {
        JobDispatcher.INSTANCE.shutdown(timeoutMs);
    }

    /**
     * Checks if the Governor is running.
     *
     * @return true if started and not shutting down
     */
    public static boolean isRunning() {
        return JobDispatcher.INSTANCE.isRunning();
    }

    /**
     * Registers a component for graceful shutdown when the Governor stops.
     *
     * @param stoppable the component to stop
     */
    public static void registerForShutdown(Stoppable stoppable) {
        JobDispatcher.INSTANCE.registerForShutdown(stoppable);
    }

    // --- Event system ---

    /**
     * Gets the message bus for publishing and subscribing to events.
     *
     * @return the message bus
     */
    public static MessageBus getMessageBus() {
        return JobDispatcher.INSTANCE.getMessageBus();
    }

    /**
     * Subscribes to events of a specific type.
     *
     * @param observer the observer to receive events
     * @param eventType the event type to subscribe to
     * @param <T> the event type
     * @return a subscription that can be used to unsubscribe
     */
    public static <T extends JobEvent> MessageBus.Subscription subscribe(JobObserver<T> observer, Class<T> eventType) {
        return JobDispatcher.INSTANCE.subscribe(observer, eventType);
    }

    /**
     * Subscribes to events of a specific type from a specific job type.
     *
     * @param observer the observer to receive events
     * @param eventType the event type to subscribe to
     * @param jobType the job type to filter by
     * @param <T> the event type
     * @return a subscription that can be used to unsubscribe
     */
    public static <T extends JobEvent> MessageBus.Subscription subscribe(JobObserver<T> observer, Class<T> eventType, Class<? extends Job> jobType) {
        return JobDispatcher.INSTANCE.subscribe(observer, eventType, jobType);
    }

    /**
     * Observes all events for a specific workflow.
     *
     * @param workflowId the workflow ID to observe
     * @param observer the observer to receive events
     * @return a subscription that can be used to unsubscribe
     */
    public static MessageBus.Subscription observeWorkflow(String workflowId, JobObserver<JobEvent> observer) {
        return JobDispatcher.INSTANCE.observeWorkflow(workflowId, observer);
    }

    /**
     * Observes typed events for a specific workflow.
     *
     * @param observer the workflow observer
     * @param eventType the event type to observe
     * @param <T> the event type
     * @return a subscription that can be used to unsubscribe
     */
    public static <T extends JobEvent> MessageBus.Subscription observeWorkflow(AbstractWorkflowObserver<T> observer, Class<T> eventType) {
        return JobDispatcher.INSTANCE.observeWorkflow(observer, eventType);
    }

    // --- Job control ---

    /**
     * Cancels a specific job.
     *
     * @param jobId the job ID to cancel
     * @param reason the cancellation reason
     * @return true if the job was found and cancelled
     */
    public static boolean cancel(String jobId, String reason) {
        return JobDispatcher.INSTANCE.cancel(jobId, reason);
    }

    /**
     * Cancels all jobs in a workflow.
     *
     * @param workflowId the workflow ID
     * @param reason the cancellation reason
     * @return the number of jobs cancelled
     */
    public static int cancelWorkflow(String workflowId, String reason) {
        return JobDispatcher.INSTANCE.cancelWorkflow(workflowId, reason);
    }

    // --- Monitoring ---

    /**
     * Gets execution statistics.
     *
     * @return map of statistic names to values
     */
    public static Map<String, Object> getStatistics() {
        return JobDispatcher.INSTANCE.getStatistics();
    }
}
