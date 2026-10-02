/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors;

/**
 * Exception thrown when an external service or API fails.
 *
 * <p>This is an uncorrectable exception - the LLM cannot fix external service issues.
 * The LLM should adapt its strategy by using alternative tools, explaining the limitation
 * to the user, or providing graceful degradation.</p>
 *
 * <p><strong>When a status came back</strong>, {@link ai.redouble.nucleo.harness.errors.http.HttpExceptions}
 * builds the subclass for it: 429 is an {@link ai.redouble.nucleo.harness.errors.http.Http429Exception},
 * 500, 502 and 503 have their own classes, and any other error status is an
 * {@link ai.redouble.nucleo.harness.errors.http.HttpUnmappedStatusException}. All of them are this
 * type and all carry the server's answer. Construct this class directly only for a failure with no
 * status: a connection refused, a timeout, an unparseable body.</p>
 *
 * <p><strong>Do NOT use for:</strong></p>
 * <ul>
 *   <li>HTTP 400/422 -&gt; use {@link InvalidInputException} (correctable)</li>
 *   <li>HTTP 401/403 -&gt; use {@link UnauthorizedException} (auth-specific)</li>
 *   <li>HTTP 404 on fetch-by-ID -&gt; use {@link ResourceNotFoundException} (correctable)</li>
 * </ul>
 *
 * <p><strong>Usage Example:</strong></p>
 * <pre>{@code
 * try {
 *     response = pubmedClient.search(query);
 * } catch (SocketTimeoutException e) {
 *     throw new ExternalServiceException("PubMed API", "Connection timeout after 30 seconds", e);
 * }
 * }</pre>
 *
 * <p><strong>LLM sees:</strong></p>
 * <pre>
 * PubMed API service error: Connection timeout after 30 seconds
 * [This error is not correctable - consider an alternative approach]
 * </pre>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-21)
 */
public class ExternalServiceException extends UncorrectableLLMException {
    private final String serviceName;
    private final String errorDetails;

    /**
     * Constructs an external service exception with service details.
     *
     * @param serviceName the name of the external service that failed
     * @param errorDetails description of what went wrong
     */
    public ExternalServiceException(String serviceName, String errorDetails) {
        super(serviceName + " service error: " + errorDetails);
        this.serviceName = serviceName;
        this.errorDetails = errorDetails;
    }

    /**
     * Constructs an external service exception with service details and cause.
     *
     * @param serviceName the name of the external service that failed
     * @param errorDetails description of what went wrong
     * @param cause the underlying cause
     */
    public ExternalServiceException(String serviceName, String errorDetails, Throwable cause) {
        super(serviceName + " service error: " + errorDetails, cause);
        this.serviceName = serviceName;
        this.errorDetails = errorDetails;
    }

    @Override
    public String getLLMMessage() {
        return String.format("%s service error: %s", serviceName, errorDetails);
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getErrorDetails() {
        return errorDetails;
    }
}
