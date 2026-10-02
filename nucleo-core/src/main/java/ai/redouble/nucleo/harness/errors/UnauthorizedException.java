/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors;


/**
 * Exception thrown when an API or service rejects the request due to authentication
 * or authorization failure (HTTP 401/403).
 *
 * <p>This is an uncorrectable exception - the LLM cannot fix authentication issues.
 * The LLM should try an alternative tool or data source, or explain the limitation
 * to the user.</p>
 *
 * <p><strong>Distinct from {@link PermissionDeniedException}:</strong></p>
 * <ul>
 *   <li>{@code UnauthorizedException} - External API rejected our credentials (HTTP 401/403).
 *       API key expired, invalid, missing, or insufficient scope.</li>
 *   <li>{@code PermissionDeniedException} - Internal permission check. User lacks access
 *       to a feature or resource within our system.</li>
 * </ul>
 *
 * <p><strong>Usage Example:</strong></p>
 * <pre>
 * if (response.statusCode() == 401 || response.statusCode() == 403) {
 *     throw new UnauthorizedException("EPO OPS", "API key rejected (HTTP " + response.statusCode() + ")");
 * }
 * </pre>
 *
 * <p><strong>LLM sees:</strong></p>
 * <pre>
 * Access denied to EPO OPS: API key rejected (HTTP 401)
 * [This error is not correctable - consider an alternative approach]
 * </pre>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-26)
 */
public class UnauthorizedException extends UncorrectableLLMException {
    private final String serviceName;
    private final String reason;

    /**
     * Constructs an unauthorized exception.
     *
     * @param serviceName the external service that rejected the request
     * @param reason description of the auth failure (e.g., "API key expired", "HTTP 403")
     */
    public UnauthorizedException(String serviceName, String reason) {
        super("Access denied to " + serviceName + ": " + reason);
        this.serviceName = serviceName;
        this.reason = reason;
    }

    /**
     * Constructs an unauthorized exception with a cause.
     *
     * @param serviceName the external service that rejected the request
     * @param reason description of the auth failure
     * @param cause the underlying cause
     */
    public UnauthorizedException(String serviceName, String reason, Throwable cause) {
        super("Access denied to " + serviceName + ": " + reason, cause);
        this.serviceName = serviceName;
        this.reason = reason;
    }

    @Override
    public String getLLMMessage() {
        return String.format("Access denied to %s: %s", serviceName, reason);
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getReason() {
        return reason;
    }
}
