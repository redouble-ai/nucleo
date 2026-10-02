/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.observability;

import ai.redouble.nucleo.harness.*;

import java.util.function.*;

/**
 * Observer interface for handling job events; {@link #observe} is its one abstract method, so a
 * lambda is an observer. Runs in a dedicated virtual thread with isolated resources.
 *
 * @param <T> the type of JobEvent this observer handles
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-14)
 */
public interface JobObserver<T extends JobEvent> {

    /**
     * Returns a predicate for filtering events at the MessageBus level.
     * Called once during subscription.
     *
     * @return predicate to filter events, or null to accept all events of type T
     */
    default Predicate<T> getPredicate() {
        return null;
    }

    /**
     * Process a job event.
     * Runs in a separate virtual thread. Must handle its own resources and exceptions.
     *
     * @param event the job event to process
     */
    void observe(T event);

    /**
     * Checks if this observer should be considered stale and eligible for cleanup.
     * Called periodically by the MessageBus cleanup mechanism.
     * <p>
     * Observers can implement this to indicate when they should be automatically
     * unsubscribed (e.g., after a period of inactivity or reaching a max event count).
     *
     * @return true if this observer is stale and should be cleaned up, false otherwise
     */
    default boolean isStale() {
        return false;  // By default, observers are never stale
    }
}