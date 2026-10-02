/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import ai.redouble.nucleo.harness.observability.*;

/**
 * High-performance message bus for job lifecycle events.
 * Implementations must be thread-safe and non-blocking for publishers.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-08-10)
 */
public interface MessageBus {

    /**
     * Publishes a message to all registered listeners.
     * This method must never block the calling thread.
     *
     * @param message the message to publish
     */
    void publish(JobEvent message);

    /**
     * Subscribes to messages of a specific type from a specific job type.
     *
     * @param <T>         the message type
     * @param jobType     the job class to filter by (null or Job.class for all job types)
     * @param messageType the message class to filter by
     * @param observer    the observer to invoke for matching messages
     * @return a subscription handle that can be used to unsubscribe
     */
    <T extends JobEvent> Subscription subscribe(Class<? extends Job> jobType, Class<T> messageType, JobObserver<T> observer);

    /**
     * Subscribes to messages of a specific type from a specific job type and workflow.
     *
     * @param <T>         the message type
     * @param jobType     the job class to filter by (null or Job.class for all job types)
     * @param messageType the message class to filter by
     * @param workflowId  the workflow ID to filter by (null for all workflows)
     * @param observer    the observer to invoke for matching messages
     * @return a subscription handle that can be used to unsubscribe
     */
    <T extends JobEvent> Subscription subscribe(Class<? extends Job> jobType, Class<T> messageType, String workflowId, JobObserver<T> observer);

    /**
     * Removes a subscription.
     *
     * @param subscription the subscription to remove
     */
    void unsubscribe(Subscription subscription);

    /**
     * Starts the message bus.
     * Must be called before publishing or consuming messages.
     */
    void start();

    /**
     * Stops the message bus gracefully.
     * Waits for in-flight messages to be processed.
     */
    void stop();

    /**
     * Removes stale subscriptions by calling isStale() on all observers.
     * This method iterates through all active subscriptions and unsubscribes
     * those whose observers report being stale.
     *
     * <p>This is typically called periodically by a cleanup task, but can also
     * be invoked manually for immediate cleanup.</p>
     *
     * @return the number of stale subscriptions that were removed
     */
    int cleanupStaleSubscriptions();

    /**
     * Handle for managing subscriptions.
     */
    interface Subscription {
        /**
         * Removes this subscription from the message bus.
         */
        void unsubscribe();

        /**
         * Gets the unique identifier for this subscription.
         *
         * @return the subscription ID
         */
        String getId();
    }
}