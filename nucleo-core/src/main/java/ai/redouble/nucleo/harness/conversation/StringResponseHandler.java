/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import ai.redouble.nucleo.harness.schema.*;
import java.io.*;
import java.util.*;

/**
 * The plain-text contract: the model answers in prose and {@link #parse} returns it
 * verbatim. Stateless, so one shared {@link #instance} serves every message; no schema
 * notation, no validation.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-21)
 */
public class StringResponseHandler implements ResponseHandler<String> {

    public static final StringResponseHandler instance = new StringResponseHandler();

    private StringResponseHandler() {}

    @Override
    public Class<String> getResponseClass() {
        return String.class;
    }

    @Override
    public String parse(final String rawContent) throws IOException {
        return rawContent;
    }

    @Override
    public String write(final String response) {
        return response;
    }

    @Override
    public List<String> getValidationErrors(String response) {
        return List.of();
    }

    @Override
    public PojoDefinition writeDefinition() {
        // A plain string has no fields to describe
        return new PojoDefinition("String", "Plain text response");
    }

    @Override
    public boolean usesSchemaNotation() {
        return false;
    }

    @Override
    public String responseInstructions() {
        return "\nResponse must be a string";
    }
}
