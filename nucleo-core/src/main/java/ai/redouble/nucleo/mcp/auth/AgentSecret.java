/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.auth;


/**
 * A static secret minted for an agent, presented to the token endpoint as HTTP Basic
 * {@code usr:secret}. The secret's value is never part of any message this package
 * composes.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
public record AgentSecret(String usr, String secret) implements McpClientCredential {
    public AgentSecret {
        if (usr == null || usr.isBlank()) {
            throw new IllegalArgumentException("A secret credential needs the agent's usr");
        }
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("A secret credential needs the secret");
        }
    }

    @Override
    public String tokenEndpointAuthMethod() {
        return CLIENT_SECRET_BASIC;
    }

    /** The secret stays out of every string a debugger or a log might render. */
    @Override
    public String toString() {
        return "AgentSecret[" + usr + "]";
    }
}
