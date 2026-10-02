/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors;


/**
 * Exception thrown when a system-level error or bug occurs during job execution.
 *
 * <p>This is an uncorrectable exception that wraps RuntimeExceptions and other unexpected
 * errors. The LLM cannot fix bugs or system-level issues, but this exception allows the
 * framework to inform the LLM about the error before the job fails, enabling graceful
 * error explanation to the user.</p>
 *
 * <p><strong>Examples:</strong></p>
 * <ul>
 *   <li>NullPointerException from a bug in tool code</li>
 *   <li>IllegalStateException from unexpected system state</li>
 *   <li>Database transaction failures</li>
 *   <li>Resource allocation failures</li>
 * </ul>
 *
 * <p><strong>Usage Example:</strong></p>
 * <pre>
 * try {
 *     Tool tool = toolClass.getDeclaredConstructor().newInstance();
 * } catch (RuntimeException e) {
 *     throw new SystemException(
 *         "Tool instantiation",
 *         "Failed to create instance of " + toolClass.getName(),
 *         e
 *     );
 * }
 * </pre>
 *
 * <p><strong>LLM sees:</strong></p>
 * <pre>
 * A system error occurred in Tool instantiation. The operation could not be completed.
 * [This error is not correctable - consider an alternative approach]
 * </pre>
 *
 * <p>The model learns the component, never the technical message or the stack; those stay on
 * {@link #getMessage()} and the cause for the log, which a thinker writes in full when a tool
 * fails this way.</p>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-21)
 */
public class SystemException extends UncorrectableLLMException {
    private final String component;
    private final String technicalMessage;

    /**
     * Constructs a system exception with component details.
     *
     * @param component the component or subsystem where the error occurred
     * @param technicalMessage technical description of the error (for logs)
     * @param cause the underlying cause (usually a RuntimeException)
     */
    public SystemException(String component, String technicalMessage, Throwable cause) {
        super("System error in " + component + ": " + technicalMessage, cause);
        this.component = component;
        this.technicalMessage = technicalMessage;
    }

    @Override
    public String getLLMMessage() {
        return "A system error occurred in " + component + ". The operation could not be completed.";
    }

    public String getComponent() {
        return component;
    }

    public String getTechnicalMessage() {
        return technicalMessage;
    }
}
