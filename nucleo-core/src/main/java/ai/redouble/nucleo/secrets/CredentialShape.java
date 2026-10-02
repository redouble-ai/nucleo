/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.secrets;

import java.util.*;

/**
 * What a credential is made of, declared once by the code that owns its id: which of the
 * three parts ({@code user}, {@code secret}, {@code host}) the credential has, the environment
 * variable each part is read from, and what each part means to a person. A store reads and
 * describes a credential by its shape, so the variables the runtime reads are exactly the
 * ones its documentation and its status pages ask for - AWS's {@code AWS_ACCESS_KEY_ID} and
 * {@code AWS_SECRET_ACCESS_KEY} for the Bedrock pair, {@code AWS_REGION} alone for the region
 * - and no part a credential does not have is ever asked for or read.
 *
 * <p>An id nobody declared has the {@link #generic} shape: the runtime's one rule, the id
 * uppercased with every non-alphanumeric character an underscore for the secret, {@code _USER}
 * and {@code _HOST} for the other parts. The generic shape does not know which parts exist,
 * so it reads all three and describes the suffixes as conditional.
 *
 * @param id       the credential id a consumer asks for
 * @param user     the user part, or null when the credential has none
 * @param secret   the secret part, or null when the credential has none
 * @param host     the host part, or null when the credential has none
 * @param declared whether an owner declared this shape; false for the generic rule
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-23)
 */
public record CredentialShape(String id, Part user, Part secret, Part host, boolean declared) {

    /** One part of a credential: the environment variable it is read from and what it means to a person. */
    public record Part(String variable, String meaning) {
        public Part {
            if (variable == null || variable.isBlank() || meaning == null || meaning.isBlank()) {
                throw new IllegalArgumentException("a credential part needs its variable and its meaning");
            }
        }
    }

    public CredentialShape {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a credential shape needs its id");
        }
        if (user == null && secret == null && host == null) {
            throw new IllegalArgumentException("a credential shape needs at least one part: " + id);
        }
    }

    /** A credential that is one value, read as the secret part. */
    public static CredentialShape secret(String id, String variable, String meaning) {
        return new CredentialShape(id, null, new Part(variable, meaning), null, true);
    }

    /** A credential that is a secret and the host it is for. */
    public static CredentialShape secretAndHost(String id, String secretVariable, String secretMeaning, String hostVariable, String hostMeaning) {
        return new CredentialShape(id, null, new Part(secretVariable, secretMeaning), new Part(hostVariable, hostMeaning), true);
    }

    /** A credential that is only where to call: a server on the deployment's own machine or network that checks no key. */
    public static CredentialShape host(String id, String variable, String meaning) {
        return new CredentialShape(id, null, null, new Part(variable, meaning), true);
    }

    /** A credential that is a pair: a user (an id, a consumer key) and its secret. */
    public static CredentialShape userAndSecret(String id, String userVariable, String userMeaning, String secretVariable, String secretMeaning) {
        return new CredentialShape(id, new Part(userVariable, userMeaning), new Part(secretVariable, secretMeaning), null, true);
    }

    /** The runtime's one rule for an id nobody declared. */
    public static CredentialShape generic(String id) {
        String name = EnvironmentSecrets.variableName(id);
        return new CredentialShape(id, new Part(name + "_USER", "the user part, when the credential has one"),
                new Part(name, "the secret"), new Part(name + "_HOST", "the host part, when the credential has one"), false);
    }

    /** The parts the credential has, user then secret then host. */
    public List<Part> parts() {
        List<Part> parts = new ArrayList<>(3);
        if (user != null) {
            parts.add(user);
        }
        if (secret != null) {
            parts.add(secret);
        }
        if (host != null) {
            parts.add(host);
        }
        return parts;
    }

    /**
     * How a person provides the credential as configuration properties under a prefix, part
     * by part: the secret is the property itself, the user its {@code .user}, the host its
     * {@code .host}, each with what it means. The generic shape names the property and its two
     * conditional suffixes.
     */
    public String describeProperties(String prefix) {
        String base = prefix + id;
        if (!declared) {
            return "the property " + base + " (with " + base + ".user / " + base + ".host when the credential has a user or host part)";
        }
        List<String> named = new ArrayList<>(3);
        if (user != null) {
            named.add(base + ".user (" + user.meaning() + ")");
        }
        if (secret != null) {
            named.add(base + " (" + secret.meaning() + ")");
        }
        if (host != null) {
            named.add(base + ".host (" + host.meaning() + ")");
        }
        return (named.size() == 1 ? "the property " : "the properties ") + join(named);
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(i == items.size() - 1 ? " and " : ", ");
            }
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    /**
     * How a person provides the credential through the environment, in the variables' own
     * names: {@code the environment variable AWS_REGION (the region every call goes to)}, or
     * {@code the environment variables AWS_ACCESS_KEY_ID (the access key id) and
     * AWS_SECRET_ACCESS_KEY (the secret access key)}. The generic shape names the secret's
     * variable and its two conditional suffixes.
     */
    public String describeVariables() {
        if (!declared) {
            String name = secret.variable();
            return "the environment variable " + name + " (and " + name + "_USER / " + name + "_HOST when the credential has a user or host part)";
        }
        List<String> named = new ArrayList<>(3);
        for (Part part : parts()) {
            named.add(part.variable() + " (" + part.meaning() + ")");
        }
        return (named.size() == 1 ? "the environment variable " : "the environment variables ") + join(named);
    }
}
