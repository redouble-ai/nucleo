/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.auth;

import ai.redouble.nucleo.harness.errors.*;
import org.junit.jupiter.api.*;

import java.net.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The flow against a scripted server that checks what the conformance referee checks, and
 * the refusals the RFCs require of the client: a protected resource document about another
 * resource, an authorization server naming another issuer or a token endpoint elsewhere, a
 * server without the grant or the method, a token response without a usable token. Every
 * failure is the framework's typed exception and none of them quotes the credential.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
class McpTokenClientTest {
    static ScriptedAuthorizationServer server;
    static KeyPair keys;
    McpTokenClient client = new McpTokenClient();

    @BeforeAll
    static void start() throws Exception {
        server = new ScriptedAuthorizationServer();
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        keys = g.generateKeyPair();
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @BeforeEach
    void reset() {
        server.publicKey = keys.getPublic();
        server.servePrmAtWellKnown = true;
        server.challengeMetadataUrl = null;
        server.prmResource = server.base + "/mcp";
        server.metadataIssuer = server.base;
        server.tokenEndpoint = server.base + "/token";
        server.grantTypes = List.of("client_credentials");
        server.authMethods = List.of("private_key_jwt", "client_secret_basic");
        server.tokenStatus = 200;
        server.tokenBody = null;
        server.expiresIn = 3600;
        server.probeStatus = 401;
        server.tokenRequests.clear();
        server.tokenAuthorizationHeaders.clear();
    }

    static AgentSigningKey signingKey() {
        return new AgentSigningKey(ScriptedAuthorizationServer.USR, keys.getPrivate(), null);
    }

    static AgentSecret secret() {
        return new AgentSecret(ScriptedAuthorizationServer.USR, ScriptedAuthorizationServer.SECRET);
    }

    @Test
    void assertionMintsThroughWellKnownDiscovery() throws Exception {
        McpAccessToken token = client.acquire(server.mcpUrl(), signingKey());
        assertTrue(server.minted.contains(token.value()));
        assertNotNull(token.expiresAt(), "expires_in becomes an expiry");
        Map<String, String> request = server.tokenRequests.get(0);
        assertEquals("client_credentials", request.get("grant_type"));
        assertEquals(server.base + "/mcp", request.get("resource"), "the RFC 8707 resource indicator is the canonical MCP URL");
        assertEquals(ScriptedAuthorizationServer.JWT_BEARER, request.get("client_assertion_type"));
        assertFalse(request.containsKey("client_id"), "the extension conveys the client through the assertion's sub");
    }

    @Test
    void secretMintsWithHttpBasic() throws Exception {
        McpAccessToken token = client.acquire(server.mcpUrl(), secret());
        assertTrue(server.minted.contains(token.value()));
        assertTrue(server.tokenAuthorizationHeaders.get(0).startsWith("Basic "));
        assertFalse(server.tokenRequests.get(0).containsKey("client_secret"), "the secret travels in the header, never the body");
    }

    @Test
    void challengeNamedMetadataIsFollowedWhenTheWellKnownFormsAreAbsent() throws Exception {
        server.servePrmAtWellKnown = false;
        server.challengeMetadataUrl = server.base + "/prm-elsewhere";
        McpAccessToken token = client.acquire(server.mcpUrl(), signingKey());
        assertTrue(server.minted.contains(token.value()));
    }

    @Test
    void noMetadataAnywhereIsUnauthorized() {
        server.servePrmAtWellKnown = false;
        UnauthorizedException refusal = assertThrows(UnauthorizedException.class, () -> client.acquire(server.mcpUrl(), signingKey()));
        assertTrue(refusal.getLLMMessage().contains("protected resource metadata"), refusal.getLLMMessage());
    }

    @Test
    void serverThatDoesNotChallengeDoesNotMatchTheCredential() {
        server.servePrmAtWellKnown = false;
        server.probeStatus = 200;
        assertThrows(ExternalServiceException.class, () -> client.acquire(server.mcpUrl(), signingKey()));
    }

    @Test
    void protectedResourceAboutAnotherResourceIsRefused() {
        server.prmResource = server.base + "/other";
        ExternalServiceException refusal = assertThrows(ExternalServiceException.class, () -> client.acquire(server.mcpUrl(), signingKey()));
        assertTrue(refusal.getLLMMessage().contains("different resource"), refusal.getLLMMessage());
    }

    @Test
    void metadataNamingAnotherIssuerIsRefused() {
        server.metadataIssuer = "http://other.invalid";
        assertThrows(ExternalServiceException.class, () -> client.acquire(server.mcpUrl(), signingKey()));
        assertTrue(server.tokenRequests.isEmpty(), "nothing was posted anywhere");
    }

    @Test
    void tokenEndpointOnAnotherHostIsRefused() {
        server.tokenEndpoint = "http://other.invalid/token";
        assertThrows(ExternalServiceException.class, () -> client.acquire(server.mcpUrl(), secret()));
        assertTrue(server.tokenRequests.isEmpty(), "the secret never left for the other host");
    }

    @Test
    void missingGrantOrMethodIsRefusedBeforeTheTokenRequest() {
        server.grantTypes = List.of("authorization_code");
        assertThrows(ExternalServiceException.class, () -> client.acquire(server.mcpUrl(), signingKey()));
        server.grantTypes = List.of("client_credentials");
        server.authMethods = List.of("client_secret_basic");
        assertThrows(ExternalServiceException.class, () -> client.acquire(server.mcpUrl(), signingKey()));
        assertTrue(server.tokenRequests.isEmpty());
    }

    @Test
    void invalidClientIsUnauthorized() {
        server.tokenStatus = 401;
        assertThrows(UnauthorizedException.class, () -> client.acquire(server.mcpUrl(), secret()));
    }

    @Test
    void serverFailureIsExternal() {
        server.tokenStatus = 503;
        server.tokenBody = "<html>down</html>";
        assertThrows(ExternalServiceException.class, () -> client.acquire(server.mcpUrl(), secret()));
    }

    @Test
    void tokenResponseWithoutAUsableTokenIsRefused() {
        server.tokenBody = "{\"token_type\":\"Bearer\"}";
        assertThrows(ExternalServiceException.class, () -> client.acquire(server.mcpUrl(), secret()));
        server.tokenBody = "{\"access_token\":\"x\",\"token_type\":\"MAC\"}";
        assertThrows(ExternalServiceException.class, () -> client.acquire(server.mcpUrl(), secret()));
        server.tokenBody = "{\"access_token\":\"x\",\"token_type\":\"bearer\",\"expires_in\":\"soon\"}";
        assertThrows(ExternalServiceException.class, () -> client.acquire(server.mcpUrl(), secret()));
        server.tokenBody = "{\"access_token\":\"x\",\"token_type\":\"bearer\"}";
        assertDoesNotThrow(() -> client.acquire(server.mcpUrl(), secret()), "a token without expires_in is used until refused");
    }

    @Test
    void absentAuthMethodsMeanTheRfc8414DefaultWhichIsBasicOnly() throws Exception {
        server.metadataRawBody = "{\"issuer\":\"" + server.base + "\",\"token_endpoint\":\"" + server.base + "/token\",\"grant_types_supported\":[\"client_credentials\"]}";
        assertDoesNotThrow(() -> client.acquire(server.mcpUrl(), secret()), "client_secret_basic is the RFC 8414 default");
        assertThrows(ExternalServiceException.class, () -> client.acquire(server.mcpUrl(), signingKey()), "private_key_jwt is not");
    }

    @Test
    void tokenEndpointMayNotDowngradeTheIssuersScheme() throws Exception {
        String metadata = "{\"issuer\":\"https://as.test\",\"token_endpoint\":\"http://as.test/token\",\"grant_types_supported\":[\"client_credentials\"]}";
        ExternalServiceException refusal = assertThrows(ExternalServiceException.class,
                () -> client.serverMetadata(ai.redouble.nucleo.mcp.server.McpBoundaryJson.strictObjectMapper().readTree(metadata), URI.create("https://as.test"), URI.create("https://as.test/.well-known/oauth-authorization-server")));
        assertTrue(refusal.getLLMMessage().contains("host other than the issuer's"), refusal.getLLMMessage());
    }

    @Test
    void redirectsAreNotFollowed() {
        assertThrows(ExternalServiceException.class, () -> client.protectedResource(URI.create(server.base + "/redirect"), server.mcpUrl()));
    }

    @Test
    void aChallengeThatIsNotAUrlIsNeverRepeated() {
        server.servePrmAtWellKnown = false;
        server.challengeMetadataUrl = "::Zq7CanaryPhrase";
        ExternalServiceException refusal = assertThrows(ExternalServiceException.class, () -> client.acquire(server.mcpUrl(), secret()));
        for (Throwable t = refusal; t != null; t = t.getCause()) {
            assertFalse(String.valueOf(t.getMessage()).contains("Zq7CanaryPhrase"), "the header value is in no message of the chain: " + t.getMessage());
        }
    }

    @Test
    void noFailureQuotesTheSecret() {
        server.tokenStatus = 401;
        UnauthorizedException refusal = assertThrows(UnauthorizedException.class, () -> client.acquire(server.mcpUrl(), secret()));
        for (Throwable t = refusal; t != null; t = t.getCause()) {
            assertFalse(String.valueOf(t.getMessage()).contains("canary"), "the secret is not in any message of the chain");
        }
        assertFalse(refusal.getLLMMessage().contains("canary"));
    }
}
