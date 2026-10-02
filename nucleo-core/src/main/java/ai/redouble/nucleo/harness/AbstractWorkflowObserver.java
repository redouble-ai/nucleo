/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.harness.observability.*;

import java.time.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * Base class for observing events from a specific workflow.
 *
 * <p>This abstract class simplifies workflow-specific event observation by:
 * <ul>
 *   <li>Automatically filtering events to match a specific workflow ID</li>
 *   <li>Managing observer lifecycle with optional inactivity timeouts</li>
 *   <li>Providing automatic cleanup on workflow termination events</li>
 *   <li>Tracking last event time for staleness detection</li>
 * </ul>
 *
 * <p><b>Key Features:</b></p>
 * <ul>
 *   <li><b>Workflow Filtering:</b> Only events from the specified workflow ID are delivered</li>
 *   <li><b>Automatic Cleanup:</b> Unsubscribes when receiving {@link WorkflowTerminationEvent}</li>
 *   <li><b>Inactivity Timeout:</b> Optional timeout after which the observer is marked stale</li>
 *   <li><b>Thread-Safe:</b> All public methods are thread-safe</li>
 * </ul>
 *
 * <p><b>Observer Equality and Deduplication:</b></p>
 * <p>By default, each observer instance is considered unique (uses Object identity).
 * Subclasses SHOULD override {@code equals()} and {@code hashCode()} if they want
 * deduplication behavior. When implementing equality, consider:
 * <ul>
 *   <li><b>workflowId:</b> Always include - observers for different workflows are different</li>
 *   <li><b>Instance-specific state:</b> Include fields that make this observer unique
 *       (e.g., WebSocket session ID, user ID, configuration flags)</li>
 *   <li><b>Shared state:</b> Don't include mutable or shared fields like lastEventTime</li>
 * </ul>
 *
 * <p><b>Example - Session-based equality:</b></p>
 * <pre>{@code
 * class WebSocketObserver extends AbstractWorkflowObserver<JobEvent> {
 *     private final String sessionId;
 *
 *     @Override
 *     public boolean equals(Object obj) {
 *         if (this == obj) return true;
 *         if (!(obj instanceof WebSocketObserver)) return false;
 *         WebSocketObserver that = (WebSocketObserver) obj;
 *         return Objects.equals(workflowId, that.workflowId) &&
 *                Objects.equals(sessionId, that.sessionId);
 *     }
 *
 *     @Override
 *     public int hashCode() {
 *         return Objects.hash(workflowId, sessionId);
 *     }
 * }
 * }</pre>
 *
 * <p><b>Usage Example:</b></p>
 * <pre>{@code
 * // Create a progress observer for a specific workflow
 * class ProgressObserver extends AbstractWorkflowObserver<JobProgressEvent<?>> {
 *     ProgressObserver(String workflowId) {
 *         super(workflowId, Duration.ofMinutes(5));  // 5-minute timeout
 *     }
 *
 *     @Override
 *     protected void handleEvent(JobProgressEvent<?> event) {
 *         System.out.println("Progress: " + event.getProgressPercent() + "%");
 *     }
 * }
 *
 * // Register with JobDispatcher
 * JobDispatcher dispatcher = JobDispatcher.getInstance();
 * ProgressObserver observer = new ProgressObserver(workflowId);
 * dispatcher.observeWorkflow(observer, JobProgressEvent.class);
 * }</pre>
 *
 * @param <T> the type of events this observer handles
 * @author Andrey Santrosyan
 * @since 0.1 (2025-10-11)
 */
public abstract class AbstractWorkflowObserver<T extends JobEvent> implements JobObserver<T> {

    protected final String workflowId;
    private final AtomicReference<Instant> lastEventTime;
    private final Duration inactivityTimeout;
    private volatile MessageBus.Subscription subscription;

    /**
     * Creates a workflow observer with specified inactivity timeout.
     *
     * @param workflowId the workflow ID to observe
     * @param inactivityTimeout duration after which the observer is considered stale
     *                         (null for no timeout)
     */
    protected AbstractWorkflowObserver(String workflowId, Duration inactivityTimeout) {
        this.workflowId = workflowId;
        this.lastEventTime = new AtomicReference<>(Instant.now());
        this.inactivityTimeout = inactivityTimeout;
    }

    /**
     * Creates a workflow observer without inactivity timeout.
     *
     * @param workflowId the workflow ID to observe
     */
    protected AbstractWorkflowObserver(String workflowId) {
        this(workflowId, null);
    }

    @Override
    public final Predicate<T> getPredicate() {
        return event -> {
            // Reject if no workflow ID specified
            if (workflowId == null) {
                return false;
            }

            JobSnapshot snapshot = event.snapshot();
            if (snapshot == null) {
                return false;
            }

            String eventWorkflowId = snapshot.getWorkflowId();
            return workflowId.equals(eventWorkflowId);
        };
    }

    @Override
    public final void observe(T event) {
        lastEventTime.set(Instant.now());

        // Let subclass handle the event
        handleEvent(event);

        // Check for self-cleanup conditions
        if (shouldSelfCleanup(event) && subscription != null) {
            JobDispatcher.getInstance().getMessageBus().unsubscribe(subscription);
            onCleanup();
        }
    }

    /**
     * Handles a workflow event. Subclasses must implement this to process events.
     *
     * @param event the workflow event to handle
     */
    protected abstract void handleEvent(T event);

    /**
     * Determines if the observer should self-cleanup based on the event.
     * By default, returns true if the event implements WorkflowTerminationEvent,
     * indicating the workflow has definitively ended.
     * Subclasses can override to customize cleanup behavior.
     *
     * @param event the event that might trigger cleanup
     * @return true if the observer should cleanup and unsubscribe
     */
    protected boolean shouldSelfCleanup(T event) {
        // Cleanup if we receive a workflow termination event
        return event instanceof WorkflowTerminationEvent;
    }

    /**
     * Called when the observer is being cleaned up (either due to staleness or self-cleanup).
     * Subclasses can override this to perform cleanup actions.
     */
    protected void onCleanup() {
        // Override for cleanup actions
    }

    @Override
    public boolean isStale() {
        if (inactivityTimeout == null) {
            return false;
        }
        return Duration.between(lastEventTime.get(), Instant.now()).compareTo(inactivityTimeout) > 0;
    }

    /**
     * Sets the subscription reference for self-cleanup.
     * Called by JobDispatcher when creating the subscription.
     *
     * @param subscription the MessageBus subscription
     */
    void setSubscription(MessageBus.Subscription subscription) {
        this.subscription = subscription;
    }

    /**
     * Gets the workflow ID being observed.
     *
     * @return the workflow ID
     */
    public String getWorkflowId() {
        return workflowId;
    }

    /**
     * Gets the time of the last received event.
     *
     * @return instant of last event
     */
    public Instant getLastEventTime() {
        return lastEventTime.get();
    }

    /**
     * Checks if this observer has an inactivity timeout configured.
     *
     * @return true if timeout is configured
     */
    public boolean hasInactivityTimeout() {
        return inactivityTimeout != null;
    }
}