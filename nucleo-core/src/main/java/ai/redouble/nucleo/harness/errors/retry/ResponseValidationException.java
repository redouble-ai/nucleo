/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors.retry;

import ai.redouble.nucleo.harness.errors.*;

import java.util.*;

/**
 * Exception representing an LLM response that parsed but failed required-field
 * validation ({@code @LLMRequired} fields null or blank). Correctable because the
 * LLM can repopulate the missing fields on retry - {@link #getLLMMessage()} lists
 * exactly which fields failed.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-09)
 */
public class ResponseValidationException extends CorrectableLLMException {
    private final List<String> validationErrors;

    public ResponseValidationException(List<String> validationErrors) {
        super("LLM response failed validation: " + String.join("; ", validationErrors));
        this.validationErrors = validationErrors;
    }

    @Override
    public String getLLMMessage() {
        StringBuilder sb = new StringBuilder("Your response failed validation:\n");
        for (String error : validationErrors) {
            sb.append("- ").append(error).append('\n');
        }
        return sb.toString();
    }

    public List<String> getValidationErrors() {
        return validationErrors;
    }
}
