/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.auth;


/**
 * What an agent presents to an MCP server's authorization server to prove it is the agent
 * it claims: a static secret ({@link AgentSecret}) or a private key whose public half the
 * server holds ({@link AgentSigningKey}). Either names the agent's {@code usr}, the
 * OAuth client id, and the token endpoint authentication method the MCP client-credentials
 * extension defines for it.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
public sealed interface McpClientCredential permits AgentSecret, AgentSigningKey {
    String PRIVATE_KEY_JWT = "private_key_jwt";
    String CLIENT_SECRET_BASIC = "client_secret_basic";

    /** The agent's usr, which is the OAuth client id. */
    String usr();

    /** The {@code token_endpoint_auth_methods_supported} value this credential needs the server to offer. */
    String tokenEndpointAuthMethod();
}
