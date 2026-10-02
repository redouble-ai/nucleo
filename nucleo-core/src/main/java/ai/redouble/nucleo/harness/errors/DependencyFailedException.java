/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors;

/**
 * Exception thrown when a job cannot execute because one of its dependencies
 * has permanently failed.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-08-12)
 */
public class DependencyFailedException extends RuntimeException {

    private final String failedDependencyId;

    /**
     * Creates a new dependency failed exception.
     *
     * @param message the error message
     * @param failedDependencyId the ID of the dependency that failed
     */
    public DependencyFailedException(String message, String failedDependencyId) {
        super(message);
        this.failedDependencyId = failedDependencyId;
    }

    /**
     * Creates a new dependency failed exception with cause.
     *
     * @param message the error message
     * @param failedDependencyId the ID of the dependency that failed
     * @param cause the cause of the failure
     */
    public DependencyFailedException(String message, String failedDependencyId, Throwable cause) {
        super(message, cause);
        this.failedDependencyId = failedDependencyId;
    }

    /**
     * Gets the ID of the dependency that failed.
     *
     * @return the failed dependency ID
     */
    public String getFailedDependencyId() {
        return failedDependencyId;
    }
}