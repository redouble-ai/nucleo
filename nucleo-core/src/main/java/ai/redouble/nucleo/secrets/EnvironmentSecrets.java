/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.secrets;

import java.util.*;
import java.util.function.*;

/**
 * Credentials from environment variables, the store a fresh checkout runs against with zero code.
 * A credential is read by its {@link CredentialShape shape}: each part the owner declared
 * comes from the variable the owner named, so the Bedrock pair reads AWS's own
 * {@code AWS_ACCESS_KEY_ID} and {@code AWS_SECRET_ACCESS_KEY}, the region {@code AWS_REGION},
 * an Azure Foundry key {@code AZURE_FOUNDRY_API_KEY} with {@code AZURE_FOUNDRY_API_KEY_HOST}
 * for its endpoint. An id nobody declared follows the one rule: the id uppercased with every
 * non-alphanumeric character turned into an underscore names the secret, and the {@code _USER}
 * and {@code _HOST} suffixes name the other two parts, so {@code epo-api-key} reads
 * {@code EPO_API_KEY} with {@code EPO_API_KEY_USER} for the consumer key.
 * <p>
 * A credential exists when any of its variables is set; an empty value counts as unset.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-10)
 */
public class EnvironmentSecrets implements Secrets {

    private final Function<String, String> variables;

    public EnvironmentSecrets() {
        this(System::getenv);
    }

    /** Over an arbitrary variable source, so the mapping is testable without touching the process environment. */
    public EnvironmentSecrets(Function<String, String> variables) {
        this.variables = variables;
    }

    /** The environment variable that carries the secret part of a credential id under the one rule. */
    public static String variableName(String id) {
        return id.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
    }

    /** The variables the credential's shape names, each with what it means. */
    @Override
    public String describe(String id) {
        return CredentialShapes.of(id).describeVariables();
    }

    @Override
    public Credential find(String id) {
        CredentialShape shape = CredentialShapes.of(id);
        String user = value(shape.user());
        String secret = value(shape.secret());
        String host = value(shape.host());
        if (secret == null && user == null && host == null) {
            return null;
        }
        return new Credential(user, secret, host);
    }

    private String value(CredentialShape.Part part) {
        if (part == null) {
            return null;
        }
        String v = variables.apply(part.variable());
        return v == null || v.isEmpty() ? null : v;
    }
}
