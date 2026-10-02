/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.auth;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.mcp.*;
import org.junit.jupiter.api.*;

import java.security.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The real SDK transport through the endpoint, end to end: the first request of a fresh
 * transport already carries a bearer minted through discovery, a server that serves no
 * well-known metadata is discovered through its challenge, a token the server rejects
 * mid-life costs one failed call and the next call carries a fresh one, and a secret
 * credential works the same way. This is the test that catches a token flow wired to a
 * seam the SDK does not honour.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
class HttpMcpCredentialFlowTest {
    static ScriptedAuthorizationServer server;
    static KeyPair keys;

    @BeforeAll
    static void start() throws Exception {
        server = new ScriptedAuthorizationServer();
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        keys = g.generateKeyPair();
        server.publicKey = keys.getPublic();
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @BeforeEach
    void reset() {
        server.servePrmAtWellKnown = true;
        server.challengeMetadataUrl = null;
        server.rejectNextBearer = false;
        server.tokenStatus = 200;
        server.mcpBearers.clear();
    }

    static HTTPMCPEndpoint endpoint(McpClientCredential credential) {
        HTTPMCPEndpoint endpoint = new HTTPMCPEndpoint();
        endpoint.setUrl(server.mcpUrl().toString());
        endpoint.setFlavor(HTTPMCPEndpoint.Flavor.STREAMABLE_HTTP);
        endpoint.setCredential(credential);
        return endpoint;
    }

    static AgentSigningKey signingKey() {
        return new AgentSigningKey(ScriptedAuthorizationServer.USR, keys.getPrivate(), "thumb");
    }

    @Test
    void firstRequestOfAFreshTransportCarriesAMintedToken() throws Exception {
        try (GenericMCPClient client = GenericMCPClient.connect(endpoint(signingKey()))) {
            assertEquals("scripted", client.getServerInfo().getName());
            assertEquals(List.of("scripted_ping"), client.listTools().stream().map(MCPToolDescriptor::getName).toList());
        }
        assertFalse(server.mcpBearers.isEmpty());
        for (String bearer : server.mcpBearers) {
            assertNotNull(bearer, "no MCP request went out without a token");
            assertTrue(server.minted.contains(bearer), "the bearer is one the token endpoint minted");
        }
    }

    @Test
    void challengeOnlyServerIsDiscoveredThroughItsChallenge() throws Exception {
        server.servePrmAtWellKnown = false;
        server.challengeMetadataUrl = server.base + "/prm-elsewhere";
        try (GenericMCPClient client = GenericMCPClient.connect(endpoint(signingKey()))) {
            assertEquals(List.of("scripted_ping"), client.listTools().stream().map(MCPToolDescriptor::getName).toList());
        }
        assertNull(server.mcpBearers.get(0), "the probe went without a token");
        assertNotNull(server.mcpBearers.get(1), "the first real request carried the minted token");
    }

    @Test
    void rejectedTokenCostsOneCallAndIsReplaced() throws Exception {
        try (GenericMCPClient client = GenericMCPClient.connect(endpoint(signingKey()))) {
            int mintsBefore = server.mints.get();
            server.rejectNextBearer = true;
            assertThrows(UnauthorizedException.class, client::listTools, "the rejected call fails as unauthorized");
            assertEquals(List.of("scripted_ping"), client.listTools().stream().map(MCPToolDescriptor::getName).toList(), "the next call carries a fresh token");
            assertEquals(mintsBefore + 1, server.mints.get(), "exactly one re-mint");
        }
    }

    @Test
    void reMintRefusedDuringACallIsUnauthorizedAndQuotesNoToken() throws Exception {
        try (GenericMCPClient client = GenericMCPClient.connect(endpoint(signingKey()))) {
            server.rejectNextBearer = true;
            server.tokenStatus = 401;
            Throwable refusal = assertThrows(UnauthorizedException.class, client::listTools, "the re-mint's 401 is the typed exception, not an external failure");
            for (Throwable t = refusal; t != null; t = t.getCause()) {
                String message = String.valueOf(t.getMessage());
                for (String token : server.minted) {
                    assertFalse(message.contains(token), "no bearer this transport held is in any message: " + message);
                }
            }
            server.tokenStatus = 200;
        }
    }

    @Test
    void secretCredentialRidesTheSameFlow() throws Exception {
        try (GenericMCPClient client = GenericMCPClient.connect(endpoint(new AgentSecret(ScriptedAuthorizationServer.USR, ScriptedAuthorizationServer.SECRET)))) {
            assertEquals(List.of("scripted_ping"), client.listTools().stream().map(MCPToolDescriptor::getName).toList());
        }
        assertTrue(server.tokenAuthorizationHeaders.stream().anyMatch(h -> h != null && h.startsWith("Basic ")));
    }
}
