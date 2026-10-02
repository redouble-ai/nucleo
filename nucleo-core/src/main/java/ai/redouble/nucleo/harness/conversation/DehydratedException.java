/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;


/**
 * Exception thrown when attempting to access functionality that requires
 * a ResponseHandler on a dehydrated message (one that has been serialized
 * and deserialized, losing its transient handler).
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-10-14)
 */
public class DehydratedException extends IllegalStateException {

    /**
     * Creates a new DehydratedException with a custom message.
     *
     * @param message the detail message
     */
    public DehydratedException(String message) {
        super(message);
    }

    /**
     * Creates a new DehydratedException with a custom message and cause.
     *
     * @param message the detail message
     * @param cause the cause
     */
    public DehydratedException(String message, Throwable cause) {
        super(message, cause);
    }
}