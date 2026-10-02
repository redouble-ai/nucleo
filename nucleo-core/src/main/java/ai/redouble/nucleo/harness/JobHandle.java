/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.observability.*;
import org.slf4j.*;

import java.util.concurrent.*;
import java.util.function.*;

/**
 * Handle for a submitted job, providing result retrieval and cancellation.
 *
 * @param <T> The type of the job result
 * @author Andrey Santrosyan
 * @since 0.1 (2025-08-11)
 */
public class JobHandle<T> implements Identifiable {
    private static final Logger log = LoggerFactory.getLogger(JobHandle.class);

    private final JobContext<T> context;
    private final CompletableFuture<T> resultFuture;

    public JobHandle(JobContext<T> context) {
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
        this.context = context;
        this.resultFuture = new CompletableFuture<>();
    }

    /**
     * Cancels the job cooperatively with a reason, through
     * {@link JobDispatcher#cancel(String, String)}. A queued job leaves its queue; a running job
     * finds the flag through {@link JobContext#isCancelled()} or
     * {@link JobContext#checkCancellation()} and unwinds. The dispatcher settles this handle, and
     * {@link #get()} throws an ExecutionException whose cause is a {@link JobCancelledException}.
     *
     * @param reason the cancellation reason for diagnostics
     * @return true if the job was found queued or running and cancellation was initiated
     */
    public boolean cancel(String reason) {
        return JobDispatcher.getInstance().cancel(context.getJobId(), reason);
    }

    /**
     * Cancels the job cooperatively, {@link java.util.concurrent.Future}-style. Cancellation is
     * cooperative in this runtime, so {@code mayInterruptIfRunning} carries no meaning here and
     * is ignored; delegates to {@link #cancel(String)} with a generic reason.
     */
    public boolean cancel(boolean mayInterruptIfRunning) {
        return cancel("Cancelled externally");
    }

    /**
     * Gets the job result, blocking until completion. Returns only once the dispatcher has
     * finished with the job: its terminal event published, the job no longer running, its
     * conversations and its recorded LLM calls released.
     *
     * @return the result
     * @throws InterruptedException  if interrupted while waiting
     * @throws ExecutionException    if the job failed, or was cancelled (cause: {@link JobCancelledException})
     * @throws JobDeadlockException  if called from within job execution with resources (deadlock prevention)
     */
    public T get() throws InterruptedException, ExecutionException {
        refuseWhileHoldingResources();
        return resultFuture.get();
    }

    /**
     * The no-waiting-while-holding rule, applied to every blocking get: a job that holds any
     * resource and waits on any other job receives {@link JobDeadlockException} naming both jobs
     * and the call site, whether or not the awaited job has already completed.
     */
    private void refuseWhileHoldingResources() {
        if (JobContext.isExecutingJobWithResources()) {
            JobContext<?> executing = JobContext.getCurrentExecution();

            // Capture stack trace to find the actual call site
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            StackTraceElement callSite = null;

            // Find first stack frame that's NOT in JobHandle/JobContext
            for (int i = 2; i < stack.length && i < 10; i++) {
                StackTraceElement elem = stack[i];
                String className = elem.getClassName();
                if (!className.startsWith("ai.redouble.nucleo.harness.JobHandle") && !className.startsWith("ai.redouble.nucleo.harness.JobContext")) {
                    callSite = elem;
                    break;
                }
            }

            // Build call location string
            String callLocation = null;
            if (callSite != null) {
                callLocation =
                        String.format("%s.%s(%s:%d)", callSite.getClassName(), callSite.getMethodName(), callSite.getFileName(), callSite.getLineNumber());
            }

            throw new JobDeadlockException(executing.getJob().getClass().getName(), executing.getJobId(), executing.getWorkflowId(), context.getJob()
                                                                                                                                            .getClass()
                                                                                                                                            .getName(), context.getJobId(), callLocation);
        }
    }

    /**
     * Gets the job result with timeout. The same rule as {@link #get()}: a resource-holding
     * job may not wait here either.
     *
     * @param timeout maximum wait time
     * @param unit    time unit
     * @return the result
     * @throws InterruptedException  if interrupted while waiting
     * @throws ExecutionException    if the job failed, or was cancelled (cause: {@link JobCancelledException})
     * @throws TimeoutException      if timeout exceeded
     * @throws JobDeadlockException  if called from within job execution with resources (deadlock prevention)
     */
    public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        refuseWhileHoldingResources();
        return resultFuture.get(timeout, unit);
    }

    /**
     * Registers an observer for all events from this workflow.
     * The observer will only receive events from jobs in the same workflow.
     * It is automatically unsubscribed when this job completes.
     * This method observes all JobEvent types.
     *
     * @param observer the observer to register
     */
    public void registerWorkflowObserver(JobObserver<JobEvent> observer) {
        registerWorkflowObserver(observer, JobEvent.class);
    }

    /**
     * Registers an observer for specific event types from this workflow.
     * The observer will only receive events from jobs in the same workflow.
     * It is automatically unsubscribed when this job completes.
     *
     * @param <E> the type of JobEvent to observe
     * @param observer the observer to register
     * @param eventClass the class of events to observe
     */
    public <E extends JobEvent> void registerWorkflowObserver(JobObserver<E> observer, Class<E> eventClass) {
        MessageBus messageBus = JobDispatcher.getInstance().getMessageBus();
        String workflowId = this.getWorkflowId();

        // Create an observer with workflow filtering predicate
        JobObserver<E> workflowFilteringObserver = new JobObserver<E>() {
            @Override
            public Predicate<E> getPredicate() {
                // Combine workflow filtering with any predicate from the original observer
                Predicate<E> originalPredicate = observer.getPredicate();
                Predicate<E> workflowPredicate = event -> {
                    JobSnapshot snapshot = event.snapshot();
                    if (snapshot == null) {
                        return false;
                    }
                    return workflowId.equals(snapshot.getWorkflowId());
                };

                // If original observer has a predicate, combine them
                if (originalPredicate != null) {
                    return workflowPredicate.and(originalPredicate);
                }
                return workflowPredicate;
            }

            @Override
            public void observe(E event) {
                observer.observe(event);
            }
        };

        // Subscribe the filtering observer for all events from all jobs
        MessageBus.Subscription subscription = messageBus.subscribe(null, // All job types
                eventClass, // Event type
                workflowFilteringObserver);

        // Automatically unsubscribe when the job completes
        this.asFuture().whenComplete((result, error) -> {
            subscription.unsubscribe();

            // Log completion
            if (error != null) {
                log.info("Workflow observer stopped due to error: {}", error.getMessage());
            }
            else {
                log.info("Workflow observer stopped - workflow {} complete", workflowId);
            }
        });

        log.info("Observer registered for workflow {}", workflowId);
    }

    /**
     * Gets the underlying CompletableFuture for advanced operations.
     *
     * @return the CompletableFuture
     */
    public CompletableFuture<T> asFuture() {
        return resultFuture;
    }

    /**
     * Gets the job context for accessing job metadata.
     *
     * @return the job context
     */
    public JobContext<T> getContext() {
        return context;
    }

    // Identifiable interface implementation (delegate to context)
    @Override
    public String getJobId() {
        return context.getJobId();
    }

    @Override
    public String getParentJobId() {
        return context.getParentJobId();
    }

    @Override
    public String getWorkflowId() {
        return context.getWorkflowId();
    }

    @Override
    public String getUserId() {
        return context.getUserId();
    }

    // Package-private methods for framework use
    void complete(T result) {
        resultFuture.complete(result);
    }

    void fail(Throwable error) {
        resultFuture.completeExceptionally(error);
    }
}