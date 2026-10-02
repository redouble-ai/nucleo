/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.events.heartbeat.*;
import ai.redouble.nucleo.guardrails.*;
import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import org.slf4j.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * Runtime context for an executing job.
 * Provides monitoring, control, and event publishing capabilities.
 *
 * @param <T> the type of result this job produces
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-08-11)
 */
public class JobContext<T> implements Identifiable {
    private static final Logger log = LoggerFactory.getLogger(JobContext.class);

    // The job holding resources on this thread, set by JobResources between acquisition and
    // release: JobHandle.get reads it to refuse a wait while holding (deadlock prevention)
    private static final ThreadLocal<JobContext<?>> CURRENT_EXECUTION = new ThreadLocal<>();

    // The job executing on this thread, of ANY kind, set around the whole execution
    // (orchestrators included - they are resource-free, so CURRENT_EXECUTION never covers
    // them). The dispatcher's submission rules read it to identify the calling job.
    // Deliberately a plain ThreadLocal, never InheritableThreadLocal: an inheritable marker
    // would leak the caller identity into threads the job spawns itself and let a rogue
    // thread impersonate its creator at the submission door.
    private static final ThreadLocal<JobContext<?>> CURRENT_JOB = new ThreadLocal<>();

    private final Instant startTime;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicInteger progressPercent = new AtomicInteger(0);
    private volatile String progressMessage;
    // JobContext specific fields
    /**
     * The job being executed.
     */
    private final Job<T> job;
    /**
     * Current job snapshot with all metadata and state.
     * This snapshot is updated as the job progresses through state transitions.
     */
    private volatile JobSnapshot snapshot;

    /**
     * Guards every state transition. Two threads can move the state: the job's own thread
     * (RUNNING, then the terminal it earns) and whoever cancels it from outside. A transition
     * is a read of the current state, a verdict on it, and a write of the next snapshot, and
     * only under one lock is that one step: without it two terminal writes can both pass the
     * terminal check and the last one wins, or the completion timestamp of the first is lost.
     * Nothing blocks inside the lock; it covers an in-memory copy and nothing else.
     */
    private final Object stateLock = new Object();
    /**
     * Unique execution ID (different from job ID for retries).
     */
    private final String executionId;
    /**
     * Cancellation token for job-specific cancellation.
     */
    private final CancellationToken cancellationToken;

    /**
     * Deadline for execution. Null when the job has no execution timeout (orchestrators) -
     * readers treat a null deadline as "never expires".
     */
    private final Instant deadline;

    /**
     * Whether job has completed.
     */
    private final AtomicBoolean completed = new AtomicBoolean(false);

    /**
     * JobHandles of jobs this job depends on.
     */
    private List<JobHandle<?>> dependencies = new ArrayList<>();

    /**
     * Results from completed dependency jobs, indexed by their final snapshot.
     * Populated by the dispatcher after waiting for dependencies to complete.
     */
    private final Map<JobSnapshot, Object> dependencyResults = new LinkedHashMap<>();
    /**
     * Exceptions from failed dependency jobs, indexed by their final snapshot.
     * Only populated for failed dependencies when toleratesDependencyFailures=true.
     */
    private final Map<JobSnapshot, Throwable> dependencyErrors = new LinkedHashMap<>();

    /**
     * LLM responses made during job execution.
     */
    private final List<LLMResponse<?>> llmResponses = Collections.synchronizedList(new ArrayList<>());

    /**
     * Generic metadata for job execution tracking (wait times, costs, etc.).
     */
    private final Map<String, Object> metadata = new ConcurrentHashMap<>();

    /**
     * Timeout signal for cooperative termination phase.
     */
    private volatile boolean timeoutSignaled = false;

    /**
     * Job result if completed successfully.
     */
    private volatile T result;

    /**
     * Job error if failed.
     */
    private volatile Throwable error;

    /**
     * Callbacks run once on the first timeout observation after the deadline.
     */
    private final OnceCallbacks timeoutCallbacks = new OnceCallbacks();

    /**
     * Callbacks run once when the job is cancelled, through the handle, the dispatcher or the
     * timeout's cooperative phase. The mirror of {@link #onTimeout}; a waiter parked in
     * {@link Admission} registers here so cancellation wakes it at once.
     */
    private final OnceCallbacks cancelCallbacks = new OnceCallbacks();

    /**
     * Whether this job holds resources (for deadlock prevention).
     */
    private final boolean holdsResources;

    /**
     * Creates a new job context.
     *
     * @param job          the job to execute
     * @param userId       the user who submitted the job; a null or blank id is refused with
     *                     {@link IllegalArgumentException}, since every job runs on behalf of a
     *                     principal
     * @param timeout      maximum execution time
     * @param requirements the dispatcher's submit-time requirements capture - passed in
     *                     rather than re-invoked here, because every getRequirements()
     *                     call mints fresh model bindings and the dispatcher owns the
     *                     one-capture-at-submit, one-per-attempt discipline
     */
    public JobContext(Job<T> job, String userId, Duration timeout, JobRequirements requirements) {
        this.startTime = Instant.now();

        if (userId == null || userId.trim().isEmpty()) {
            throw new IllegalArgumentException("User ID cannot be null or empty");
        }
        this.job = job;
        this.holdsResources = requirements != null && requirements.requiresResources();
        // Create snapshot (uses Job.getDisplayName() instance override if available)
        this.snapshot = new JobSnapshot(job);
        this.executionId = UUID.randomUUID().toString();
        this.deadline = timeout == null ? null : getStartTime().plus(timeout);
        this.cancellationToken = new CancellationToken();
    }

    /**
     * Publishes data to the message bus.
     * <p>
     * Heartbeat control values are intercepted BEFORE the progress wrap - a
     * {@link ScheduleHeartbeat} becomes a framework-minted {@link Heartbeat} carrying
     * this context's identity and the publishing orchestrator's sealed guard (the
     * publisher never touches either), and a {@link CancelHeartbeat} rides as itself.
     * Neither mints a progress event. Scheduling is deferred submission, so it mirrors
     * the submission door's first wall: a non-orchestrator cannot schedule.
     *
     * @param <P>  the type of the payload data
     * @param data the data to publish
     */
    public <P> void publish(P data) {
        if (data instanceof ScheduleHeartbeat spec) {
            if (!(job instanceof ScopeAuthority authority)) {
                throw new UncorrectableRuntimeLLMException(
                        "Only orchestrators may schedule heartbeats: " + job.getClass().getSimpleName() + " (job " + getJobId() + ") attempted to schedule "
                        + spec.jobClass().getSimpleName() + ". Scheduling is deferred submission - coordinate through a Doer or Thinker.");
            }
            publish(new HeartbeatRequested(new Heartbeat(spec, spec.runAt(), getUserId(), authority.getScopeGuard())));
            return;
        }
        if (data instanceof CancelHeartbeat cancel) {
            publish(cancel);
            return;
        }
        JobProgressEvent<P> event = new JobProgressEvent<>(this.getSnapshot(), data);
        publish(event);
    }

    /**
     * Publishes data with progress information.
     *
     * @param <P>     the type of the payload data
     * @param data    the data to publish
     * @param percent progress percentage (0-100)
     */
    public <P> void publish(P data, int percent) {
        // Update internal state
        progressPercent.set(percent);
        progressMessage = data != null ? data.toString() : null;

        // Update state to RUNNING if still queued or scheduled
        transition(state -> state == JobState.QUEUED || state == JobState.SCHEDULED, JobState.RUNNING);

        // Create and publish event
        JobProgressEvent<P> event = new JobProgressEvent<>(this.getSnapshot(), data, percent);
        publish(event);
    }

    /**
     * Publishes a user-facing progress event.
     * Convenience method to reduce boilerplate for user notifications.
     *
     * @param title   short title (included in message for context)
     * @param message user-friendly message
     * @param percent progress percentage (0-100)
     */
    public void publishUserProgress(String title, String message, int percent) {
        publish(title + ": " + message, percent);
    }

    /**
     * Publishes a user-facing notification event.
     *
     * @param severity the severity level (info, success, warning, error)
     * @param title    short title for the notification
     * @param message  the notification message
     */
    public void publishUserNotification(UserNotificationEvent.Severity severity, String title, String message) {
        UserNotificationEvent event = switch (severity) {
            case INFO -> UserNotificationEvent.info(getSnapshot(), title, message);
            case SUCCESS -> UserNotificationEvent.success(getSnapshot(), title, message);
            case WARNING -> UserNotificationEvent.warning(getSnapshot(), title, message);
            case ERROR -> UserNotificationEvent.error(getSnapshot(), title, message);
        };
        publish(event);
    }

    /**
     * Publishes any JobEvent to the message bus.
     * Generic method for publishing any event type.
     *
     * @param event the event to publish
     */
    public void publish(JobEvent event) {
        try {
            JobDispatcher.getInstance().getMessageBus().publish(event);
        }
        catch (Exception e) {
            log.warn("Failed to publish event {}: {}", event.getClass().getSimpleName(), e.getMessage());
        }
    }

    /**
     * Checks if the job has been cancelled.
     * Jobs should periodically check this and terminate gracefully.
     *
     * @return true if cancelled
     */
    public boolean isCancelled() {
        return cancelled.get() || cancellationToken.isCancelled();
    }

    /**
     * Throws CancellationException if job has been cancelled.
     *
     * @throws CancellationException if cancelled
     */
    public void checkCancellation() throws CancellationException {
        // Check general cancellation
        if (cancelled.get()) {
            throw new CancellationException("Operation " + job.getId() + " was cancelled");
        }
        // Also check job-specific cancellation
        if (cancellationToken.isCancelled()) {
            throw new CancellationException("Job " + executionId + " was cancelled");
        }
    }

    /**
     * Checks if execution has exceeded timeout.
     *
     * @return true if timed out
     */
    public boolean isTimedOut() {
        boolean timedOut = deadline != null && Instant.now().isAfter(deadline);
        if (timedOut && !completed.get()) {
            // The first observation past the deadline runs every registered callback, once.
            // The state is not touched here: the dispatcher moves it when it handles the timeout.
            for (Runnable callback : timeoutCallbacks.takeForFiring()) {
                try {
                    callback.run();
                }
                catch (Exception e) {
                    log.warn("Error executing timeout callback: {}", e.getMessage());
                }
            }
        }
        return timedOut;
    }

    /**
     * Checks if cooperative termination phase has been signaled.
     *
     * @return true if timeout signaled
     */
    public boolean isTimeoutSignaled() {
        return timeoutSignaled;
    }

    /**
     * Registers a callback to be executed once on timeout. A callback registered after the
     * deadline runs immediately, once: either as part of the first timeout observation, which
     * runs every registered callback, or on its own when that observation already happened.
     * Registration and firing share one monitor, so a callback is in the fired list or is run
     * here, never both and never neither. A completed job never times out, so a callback
     * registered once the job has completed is dropped, not queued.
     *
     * @param callback the timeout callback
     */
    public void onTimeout(Runnable callback) {
        if (completed.get()) {
            return;
        }
        if (!timeoutCallbacks.queue(callback)) {
            callback.run();
            return;
        }
        isTimedOut();
    }

    /** How many timeout callbacks wait for the firing; for tests of the registration rule. */
    int pendingTimeoutCallbacks() {
        return timeoutCallbacks.pending();
    }

    /**
     * Registers a callback to be executed once when the job is cancelled. A callback registered
     * after cancellation runs immediately, once: either in the firing that the cancellation is
     * about to perform, when it is registered while that cancellation is under way, or here.
     * Registration and firing share one monitor, so a callback is in the fired list or is run
     * here, never both and never neither.
     *
     * @param callback the cancel callback
     */
    public void onCancel(Runnable callback) {
        if (!cancelCallbacks.queue(callback)) {
            callback.run();
        }
    }

    private void runCancelCallbacks() {
        for (Runnable callback : cancelCallbacks.takeForFiring()) {
            callback.run();
        }
    }

    /**
     * Gets elapsed execution time.
     *
     * @return duration since start
     */
    public Duration getElapsedTime() {
        return Duration.between(getStartTime(), Instant.now());
    }

    /**
     * Gets remaining time before timeout.
     *
     * @return remaining duration, or null if the job has no deadline (orchestrators)
     */
    public Duration getRemainingTime() {
        return deadline == null ? null : Duration.between(Instant.now(), deadline);
    }

    /**
     * Marks job as completed successfully.
     *
     * @param result job result
     */
    void complete(T result) {
        if (completed.compareAndSet(false, true)) {
            this.result = result;
            // A cancel that landed first keeps the state: the job finished, but the outcome the
            // caller was promised is the cancellation. The dispatcher reads the state back.
            transition(state -> !state.isTerminal(), JobState.COMPLETED);
        }
    }

    /**
     * Marks job as failed.
     *
     * @param error failure cause
     */
    void fail(Throwable error) {
        if (completed.compareAndSet(false, true)) {
            this.error = error;
            transition(state -> !state.isTerminal(), JobState.FAILED);
        }
    }

    /**
     * Requests job cancellation. The token is set regardless; the state becomes CANCELLED
     * only when the job has not already reached a terminal state, so a cancel that arrives
     * after completion, failure or timeout changes nothing and throws nothing.
     *
     * @param reason cancellation reason
     */
    void cancel(String reason) {
        cancellationToken.cancel(reason);
        transition(state -> !state.isTerminal(), JobState.CANCELLED);
        runCancelCallbacks();
    }

    /**
     * Gets the current job state.
     * Returns the state from the snapshot - the single source of truth.
     * State transitions happen explicitly via setState() in the dispatcher.
     *
     * @return current job state
     */
    public JobState getState() {
        return snapshot.getState();
    }

    /**
     * Sets the job state.
     * Package-private for scheduler use.
     *
     * @param newState new state
     * @throws IllegalStateException if attempting to transition from a terminal state
     */
    void setState(JobState newState) {
        synchronized (stateLock) {
            JobState currentState = snapshot.getState();
            // Prevent transitions FROM terminal states (once terminal, stay terminal)
            // This is a framework bug that must be exposed, not masked
            if (currentState.isTerminal()) {
                throw new IllegalStateException(
                        "Attempted illegal state transition from terminal state " + currentState + " to " + newState + " for job " + job.getId()
                        + ". Terminal states are final and cannot be changed.");
            }
            apply(newState);
        }
    }

    /**
     * Moves to {@code newState} when the current state satisfies {@code from}, as one step
     * under the state lock. The conditional form for the
     * transitions that race each other: a completion and an external cancel, or the RUNNING
     * upgrade a progress report makes. The first terminal transition wins and the second is a
     * no-op.
     */
    private void transition(Predicate<JobState> from, JobState newState) {
        synchronized (stateLock) {
            if (from.test(snapshot.getState())) {
                apply(newState);
            }
        }
    }

    /**
     * The write itself; callers hold {@link #stateLock}. The next snapshot is built whole and
     * published once, so a reader sees the new state together with its timestamp.
     */
    private void apply(JobState newState) {
        JobSnapshot next = snapshot.withState(newState);
        if (newState == JobState.RUNNING && next.getStartedAt() == null) {
            next = next.withStartedAt(Instant.now());
        }
        if (newState.isTerminal() && next.getCompletedAt() == null) {
            next = next.withCompletedAt(Instant.now());
        }
        snapshot = next;
    }

    /**
     * Updates the snapshot with dependency job IDs.
     * Package-private - used by dispatcher during dependency resolution.
     *
     * @param dependencyJobIds list of job IDs this job depends on
     */
    void setDependencyJobIds(List<String> dependencyJobIds) {
        synchronized (stateLock) {
            snapshot = snapshot.withDependencyJobIds(dependencyJobIds);
        }
    }

    // Getters
    public Job<T> getJob() {return job;}

    /**
     * Gets the job ID.
     *
     * @return the job ID
     */
    @Override
    public String getJobId() {
        return job.getId();
    }

    /**
     * Gets the parent job ID if this job was spawned by another job.
     *
     * @return the parent job ID, or null for root jobs
     */
    @Override
    public String getParentJobId() {
        return job.getParentJobId();
    }

    /**
     * Gets the workflow ID that groups all jobs in this workflow tree.
     *
     * @return the workflow ID
     */
    @Override
    public String getWorkflowId() {
        return job.getWorkflowId();
    }

    /**
     * Get the user ID who submitted the job.
     *
     * @return the user ID
     */
    @Override
    public String getUserId() {
        return snapshot.getUserId();
    }

    public String getExecutionId() {return executionId;}

    public Instant getStartTime() {return startTime;}

    public int getProgressPercent() {return progressPercent.get();}

    public String getProgressMessage() {return progressMessage;}

    /**
     * Marks the operation as cancelled.
     */
    public void cancel() {
        cancelled.set(true);
        runCancelCallbacks();
    }

    /**
     * Signals cooperative termination phase during timeout enforcement.
     * Sets both timeout and cancellation flags for job to detect.
     */
    public void signalTimeout() {
        this.timeoutSignaled = true;
        this.cancelled.set(true);
        runCancelCallbacks();
    }

    public boolean isCompleted() {return completed.get();}

    public Optional<T> getResult() {return Optional.ofNullable(result);}

    public Optional<Throwable> getError() {return Optional.ofNullable(error);}

    /**
     * Gets the current job snapshot with metadata attached.
     * Returns an immutable snapshot of the job's identity, state, and current metadata.
     *
     * @return immutable job snapshot
     */
    public JobSnapshot getSnapshot() {
        return snapshot.withMetadata(getAllMetadata());
    }

    /**
     * Sets the dependencies for this job.
     *
     * @param deps collection of JobHandles this job depends on
     */
    public void setDependencies(Collection<JobHandle<?>> deps) {
        this.dependencies = deps != null ? new ArrayList<>(deps) : new ArrayList<>();
    }

    /**
     * Gets the dependencies for this job.
     *
     * @return list of JobHandles this job depends on
     */
    public List<JobHandle<?>> getDependencies() {
        return new ArrayList<>(dependencies);
    }

    /**
     * Gets the results from completed dependency jobs.
     * <p>
     * Dependencies are <b>guaranteed to be completed</b> before this job executes.
     * The JobDispatcher waits for all dependencies to finish, extracts their results,
     * and passes them to this job via the context. This eliminates the need for jobs
     * to call blocking {@code handle.get()} methods, preventing deadlock scenarios.
     * <p>
     * Results are indexed by the final {@link JobSnapshot} of each dependency job,
     * which provides rich metadata including:
     * <ul>
     *   <li>Job ID and class name</li>
     *   <li>Completion timestamp</li>
     *   <li>Execution duration</li>
     *   <li>Job-specific metadata</li>
     * </ul>
     * <p>
     * <b>Usage Examples:</b>
     * <pre>{@code
     * // Single dependency - get first result
     * Map<JobSnapshot, Object> results = context.getDependencyResults();
     * MyData data = (MyData) results.values().iterator().next();
     *
     * // Multiple dependencies - identify by job class
     * for (Map.Entry<JobSnapshot, Object> entry : results.entrySet()) {
     *     if (entry.getKey().jobClass().equals(ExtractDataJob.class)) {
     *         Data data = (Data) entry.getValue();
     *         // Use data
     *     }
     * }
     *
     * // Check for failed dependencies (if toleratesDependencyFailures=true)
     * for (Object result : results.values()) {
     *     if (result == null) {
     *         // Dependency failed, handle gracefully
     *     }
     * }
     * }</pre>
     *
     * @return unmodifiable map from job snapshot to result object. Empty if no dependencies.
     * Results are {@code null} for failed dependencies when {@code toleratesDependencyFailures=true}.
     * @see JobDispatcher#submit(Job, List)
     * @see JobRequirements#setToleratesDependencyFailures(boolean)
     */
    public Map<JobSnapshot, Object> getDependencyResults() {
        return Collections.unmodifiableMap(dependencyResults);
    }

    /**
     * Gets exceptions from failed dependency jobs.
     * <p>
     * This map is only populated when {@code toleratesDependencyFailures=true}
     * and provides access to the actual exceptions thrown by failed dependencies.
     * <p>
     * Jobs can use this information to make informed decisions about how to handle
     * partial failures. For example, a job might proceed if only 2 out of 10
     * dependencies failed, or take different actions based on the exception type.
     * <p>
     * <b>Usage Example:</b>
     * <pre>{@code
     * // Check if any dependencies failed
     * Map<JobSnapshot, Throwable> errors = context.getDependencyErrors();
     * if (!errors.isEmpty()) {
     *     for (Map.Entry<JobSnapshot, Throwable> entry : errors.entrySet()) {
     *         JobSnapshot snapshot = entry.getKey();
     *         Throwable error = entry.getValue();
     *         log.warn("Dependency {} failed: {}", snapshot.jobId(), error.getMessage());
     *     }
     * }
     * }</pre>
     *
     * @return unmodifiable map from job snapshot to exception. Empty if no dependencies failed.
     * @see JobRequirements#setToleratesDependencyFailures(boolean)
     */
    public Map<JobSnapshot, Throwable> getDependencyErrors() {
        return Collections.unmodifiableMap(dependencyErrors);
    }

    /**
     * Gets the single dependency result, cast to the expected type.
     * <p>
     * Convenience method for jobs with exactly one dependency. The type is inferred
     * from the assignment target, eliminating the need for explicit casting.
     * <p>
     * <b>Usage Example:</b>
     * <pre>{@code
     * // Type inferred from assignment
     * Summary result = context.singleDependencyResult();
     * }</pre>
     *
     * @param <D> the dependency result type (inferred from assignment)
     * @return the dependency result, cast to type D
     * @throws IllegalStateException if there are zero or multiple dependencies
     */
    @SuppressWarnings("unchecked")
    public <D> D singleDependencyResult() {
        if (dependencyResults.isEmpty()) {
            throw new IllegalStateException("No dependencies found");
        }
        if (dependencyResults.size() > 1) {
            throw new IllegalStateException(
                    "Multiple dependencies (" + dependencyResults.size() + ") found - use allDependencyResults() or getDependencyResults()");
        }
        return (D)dependencyResults.values().iterator().next();
    }

    /**
     * Gets all dependency results as a typed list.
     * <p>
     * Convenience method for jobs with multiple dependencies of the same type.
     * Results are returned in the order dependencies were declared. The type is
     * inferred from the assignment target.
     * <p>
     * <b>Usage Example:</b>
     * <pre>{@code
     * // Type inferred from assignment
     * List<ExtractionResult> results = context.allDependencyResults();
     * for (ExtractionResult result : results) {
     *     // Process each result (no casting needed)
     * }
     * }</pre>
     *
     * @param <D> the dependency result type (inferred from assignment)
     * @return list of all dependency results in declaration order
     */
    @SuppressWarnings("unchecked")
    public <D> List<D> allDependencyResults() {
        List<D> results = new ArrayList<>(dependencyResults.size());
        for (Object result : dependencyResults.values()) {
            results.add((D)result);
        }
        return results;
    }

    /**
     * Sets dependency results after the dispatcher has waited for all dependencies.
     * <p>
     * This method is called by {@link JobDispatcher} in Phase 1 of job execution,
     * after all dependencies have completed but before the job's {@code execute()}
     * method is invoked. The dispatcher:
     * <ol>
     *   <li>Blocks on each dependency's {@code JobHandle.get()}</li>
     *   <li>Extracts the result and final {@link JobSnapshot}</li>
     *   <li>Stores in a map and passes to this method</li>
     * </ol>
     * <p>
     * Package-private visibility enforces that only the framework can set these results.
     *
     * @param results map from dependency job snapshot to result object. Null values indicate
     *                failed dependencies (only when {@code toleratesDependencyFailures=true}).
     */
    void setDependencyResults(Map<JobSnapshot, Object> results) {
        this.dependencyResults.clear();
        if (results != null) {
            this.dependencyResults.putAll(results);
        }
    }

    /**
     * Sets exceptions from failed dependencies.
     * Package-private - called by JobDispatcher after dependency resolution.
     *
     * @param errors map from dependency job snapshot to exception
     */
    void setDependencyErrors(Map<JobSnapshot, Throwable> errors) {
        this.dependencyErrors.clear();
        if (errors != null) {
            this.dependencyErrors.putAll(errors);
        }
    }

    /**
     * Adds an LLM response to this context.
     * Thread-safe.
     *
     * @param response the LLM response to add
     */
    public void addLlmResponse(LLMResponse<?> response) {
        llmResponses.add(Objects.requireNonNull(response, "LLM response cannot be null"));
    }

    /**
     * The LLM responses this job has recorded so far, while it runs: a copy, for the job itself
     * and whoever observes it mid-flight. Empty once the job has finished - the list is released
     * before its handle completes ({@link #clearCompletionData}) - so a finished job's calls are
     * read from its terminal event ({@code TerminalEvent.getLlmResponses}), never from here.
     *
     * @return list of LLM responses
     */
    public List<LLMResponse<?>> getLlmResponses() {
        return new ArrayList<>(llmResponses);
    }

    /**
     * Clears the accumulated LLM responses to release memory. Called by the dispatcher when the
     * job finishes, after its terminal events have been published (they carry their own copy) and
     * before its handle completes.
     */
    public void clearCompletionData() {
        llmResponses.clear();
    }

    /**
     * Stores a metadata value.
     *
     * @param key   the metadata key
     * @param value the metadata value
     */
    public void putMetadata(String key, Object value) {
        metadata.put(key, value);
    }

    /**
     * Gets a metadata value.
     *
     * @param key the metadata key
     * @return the metadata value, or null if not present
     */
    public Object getMetadata(String key) {
        return metadata.get(key);
    }

    /**
     * Gets all metadata as a copy.
     *
     * @return copy of all metadata
     */
    public Map<String, Object> getAllMetadata() {
        return new HashMap<>(metadata);
    }

    /**
     * Records time an account kept this job waiting in admission. Keyed by the account's
     * {@link LimiterIdentity#limiterName()} and summed per account; accounts overlap in time,
     * so the values do not sum to the wall wait recorded by {@link #recordAdmissionWait}.
     *
     * @param resourceName the account's limiter name (a model's catalog id, {@code "http"}, {@code "db:default"})
     * @param waitTimeMs   time the account was short for this job, in milliseconds
     */
    public void recordResourceWait(String resourceName, long waitTimeMs) {
        @SuppressWarnings("unchecked")
        Map<String, Long> waitTimes = (Map<String, Long>)metadata.computeIfAbsent("wait_times", k -> new ConcurrentHashMap<String, Long>());
        waitTimes.merge(resourceName, waitTimeMs, Long::sum);
    }

    /**
     * Records the wall-clock time from entering admission to being granted, summed over the
     * job's attempts. This is the job's total wait a recorder reports.
     *
     * @param waitTimeMs wall wait in milliseconds
     */
    public void recordAdmissionWait(long waitTimeMs) {
        metadata.merge("admission_wait_ms", waitTimeMs, (a, b) -> ((Long)a) + ((Long)b));
    }

    /**
     * The runtime's cancellation signal: thrown by {@link #checkCancellation()} inside a
     * running job, raised by the dispatcher for a job cancelled before it started or one
     * that finished without observing its cancel, and wrapped by
     * {@link ai.redouble.nucleo.harness.errors.JobCancelledException} on the way to the caller.
     * Uncorrectable, so it propagates out of a tool's {@code execute} as itself and
     * {@code LLMReadableCheckedException.unwrap} returns it as itself, which is how the
     * dispatcher recognizes a cancellation that crossed a doer's catch-all. LLM-readable, so a
     * cancelled exchange leaves a marker in its conversation and on resume the model knows the
     * previous attempt was cut short.
     */
    public static class CancellationException extends UncorrectableLLMException {
        public CancellationException(String message) {
            super(message);
        }

        @Override
        public String getLLMMessage() {
            return getMessage() != null ? "The operation was cancelled: " + getMessage() : "The operation was cancelled.";
        }
    }

    /**
     * Marks that resources have been acquired for this job.
     * Sets ThreadLocal to prevent blocking calls while resources are held.
     * Package-private - called by JobResources.
     */
    void markResourcesAcquired() {
        if (holdsResources) {
            CURRENT_EXECUTION.set(this);
        }
    }

    /**
     * Marks that resources have been released for this job.
     * Clears ThreadLocal to allow blocking calls again.
     * Package-private - called by JobResources.
     */
    void markResourcesReleased() {
        if (holdsResources) {
            CURRENT_EXECUTION.remove();
        }
    }

    /**
     * Checks if the current thread is executing a job.
     *
     * @return true if currently inside job execution
     */
    public static boolean isExecutingJob() {
        return CURRENT_EXECUTION.get() != null;
    }

    /**
     * Checks if the current thread is executing a job that holds resources.
     *
     * @return true if currently inside job execution with resources
     */
    public static boolean isExecutingJobWithResources() {
        JobContext<?> ctx = CURRENT_EXECUTION.get();
        return ctx != null && ctx.holdsResources;
    }

    /**
     * Marks this context's job as the one executing on the current thread. Called by
     * the dispatcher around the whole execution; package-private.
     */
    void markJobExecuting() {
        CURRENT_JOB.set(this);
    }

    /**
     * Clears the executing-job marker. Package-private, dispatcher-called in a finally.
     */
    void clearJobExecuting() {
        CURRENT_JOB.remove();
    }

    /**
     * The context of the job executing on the current thread - any kind, resource-free
     * orchestrators included - or null on a thread running no job (an entry point).
     * The submission rules key on this.
     */
    public static JobContext<?> currentJob() {
        return CURRENT_JOB.get();
    }

    /**
     * Gets the currently executing job context for this thread.
     *
     * @return the job context, or null if not executing a job
     */
    public static JobContext<?> getCurrentExecution() {
        return CURRENT_EXECUTION.get();
    }

    /**
     * Cancellation token for cooperative cancellation.
     */
    public static class CancellationToken {
        private volatile boolean cancelled = false;
        private volatile String reason;
        private final OnceCallbacks callbacks = new OnceCallbacks();

        public boolean isCancelled() {
            return cancelled;
        }

        public String getReason() {
            return reason;
        }

        void cancel(String reason) {
            if (!cancelled) {
                this.cancelled = true;
                this.reason = reason;
                for (Runnable callback : callbacks.takeForFiring()) {
                    try {
                        callback.run();
                    }
                    catch (Exception e) {
                        log.warn("Error in cancellation callback: {}", e.getMessage());
                    }
                }
            }
        }

        /**
         * Registers a callback that runs once when the token is cancelled, at once when it
         * already is; the same one-monitor rule as {@link JobContext#onCancel}.
         *
         * @param callback the callback
         */
        public void onCancellation(Runnable callback) {
            if (!callbacks.queue(callback)) {
                callback.run();
            }
        }
    }

    /**
     * A list of callbacks that fires exactly once. Registration and firing share the list's
     * monitor: a callback queued before the firing is in the fired list, a callback that
     * arrives after it is handed back to its registrar to run at once, and no callback is in
     * both places or in neither. The callbacks themselves run outside the monitor.
     */
    static final class OnceCallbacks {
        private final List<Runnable> pending = new ArrayList<>();
        private boolean fired;

        /**
         * Queues the callback for the firing.
         *
         * @param callback the callback
         * @return true when queued; false when the list has already fired, in which case the
         * caller runs the callback itself
         */
        synchronized boolean queue(Runnable callback) {
            if (fired) {
                return false;
            }
            pending.add(callback);
            return true;
        }

        /**
         * Takes the callbacks to run, in registration order, and marks the list fired. Every
         * later call answers an empty list, so the firing happens once.
         *
         * @return the callbacks to run
         */
        synchronized List<Runnable> takeForFiring() {
            if (fired) {
                return List.of();
            }
            fired = true;
            List<Runnable> taken = new ArrayList<>(pending);
            pending.clear();
            return taken;
        }

        synchronized int pending() {
            return pending.size();
        }
    }
}