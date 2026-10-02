/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors;

/**
 * Exception thrown when a job cannot be executed due to insufficient permissions.
 *
 * <p>This is an uncorrectable exception - the LLM cannot fix permission issues.
 * The LLM should explain the limitation to the user, suggest alternative approaches,
 * or request that the user grant necessary permissions.</p>
 *
 * <p><strong>Distinct from {@link UnauthorizedException}:</strong></p>
 * <ul>
 *   <li>{@code PermissionDeniedException} - Internal permission check within our system.
 *       User lacks access to a feature or resource.</li>
 *   <li>{@code UnauthorizedException} - External API rejected our credentials (HTTP 401/403).
 *       API key expired, invalid, or insufficient scope.</li>
 * </ul>
 *
 * <p><strong>Examples:</strong></p>
 * <ul>
 *   <li>User lacks access to a specific resource</li>
 *   <li>Feature not available for current subscription tier</li>
 *   <li>User role does not permit the operation</li>
 * </ul>
 *
 * <p><strong>Usage Example:</strong></p>
 * <pre>
 * if (!user.hasPermission("admin.users.delete")) {
 *     throw new PermissionDeniedException(
 *         "User deletion requires admin privileges"
 *     );
 * }
 * </pre>
 *
 * <p><strong>LLM sees:</strong></p>
 * <pre>
 * Permission denied: User deletion requires admin privileges
 * [This error is not correctable - consider an alternative approach]
 * </pre>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-19)
 */
public class PermissionDeniedException extends UncorrectableLLMException {
    /**
     * Creates a permission denied exception with a message.
     *
     * @param message explanation of why permission was denied
     */
    public PermissionDeniedException(String message) {
        super(message);
    }

    /**
     * Creates a permission denied exception with a message and cause.
     *
     * @param message explanation of why permission was denied
     * @param cause the underlying cause (e.g., nested permission failure)
     */
    public PermissionDeniedException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public String getLLMMessage() {
        return "Permission denied: " + getMessage();
    }
}