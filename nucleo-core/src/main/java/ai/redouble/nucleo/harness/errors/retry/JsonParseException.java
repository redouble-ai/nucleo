/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors.retry;

import ai.redouble.nucleo.harness.errors.*;

/**
 * The model's reply was not the JSON object asked for: no JSON in it, JSON that would not parse,
 * or valid JSON of another shape. Correctable because the model can fix its own reply on retry.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-05)
 */
public class JsonParseException extends CorrectableLLMException {
    private final String parseError;
    public JsonParseException(String parseError, Throwable cause) {
        super("Failed to parse LLM response as JSON: " + parseError, cause);
        this.parseError = parseError;
    }
    /**
     * Names the shape that was missed, since the model's reply may well have been valid JSON
     * (a bare string is) while not being the object asked for; told only that its JSON did
     * not parse, a model repeats the same reply.
     */
    @Override
    public String getLLMMessage() {
        return "Your response was not the JSON object asked for (" + parseError + "). Reply with that JSON object only, matching the schema given.";
    }
    public String getParseError() {
        return parseError;
    }
}
