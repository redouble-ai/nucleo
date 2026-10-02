/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors;

/**
 * Exception thrown when a tool input parameter fails validation.
 *
 * <p>This is a correctable exception - the LLM can adjust the parameter value and retry
 * the operation successfully.</p>
 *
 * <p><strong>Usage Example:</strong></p>
 * <pre>
 * if (input.getMaxResults() > 100) {
 *     throw new InvalidInputException(
 *         "maxResults",
 *         input.getMaxResults(),
 *         "Must be between 1 and 100"
 *     );
 * }
 * </pre>
 *
 * <p><strong>LLM sees:</strong></p>
 * <pre>
 * Parameter 'maxResults' has invalid value '1000'. Must be between 1 and 100
 * [This error may be correctable - you can retry with different parameters or try a different approach]
 * </pre>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-03-14)
 */
public class InvalidInputException extends CorrectableLLMException {
    private final String parameterName;
    private final Object invalidValue;
    private final String validationRule;

    /**
     * Constructs a validation exception with parameter details.
     *
     * @param parameterName the name of the parameter that failed validation
     * @param invalidValue the value that was provided
     * @param validationRule the validation rule or constraint description
     */
    public InvalidInputException(String parameterName, Object invalidValue, String validationRule) {
        super("Validation failed for parameter '" + parameterName + "': " + validationRule);
        this.parameterName = parameterName;
        this.invalidValue = invalidValue;
        this.validationRule = validationRule;
    }

    /**
     * Constructs a validation exception with parameter details and cause.
     *
     * @param parameterName the name of the parameter that failed validation
     * @param invalidValue the value that was provided
     * @param validationRule the validation rule or constraint description
     * @param cause the underlying cause
     */
    public InvalidInputException(String parameterName, Object invalidValue, String validationRule, Throwable cause) {
        super("Validation failed for parameter '" + parameterName + "': " + validationRule, cause);
        this.parameterName = parameterName;
        this.invalidValue = invalidValue;
        this.validationRule = validationRule;
    }

    @Override
    public String getLLMMessage() {
        return String.format(
            "Parameter '%s' has invalid value '%s'. %s",
            parameterName,
            invalidValue,
            validationRule
        );
    }

    public String getParameterName() {
        return parameterName;
    }

    public Object getInvalidValue() {
        return invalidValue;
    }

    public String getValidationRule() {
        return validationRule;
    }
}
