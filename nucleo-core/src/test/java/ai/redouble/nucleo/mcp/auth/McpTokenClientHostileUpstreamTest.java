/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.auth;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.http.*;
import org.junit.jupiter.api.*;

import java.security.*;
import java.security.spec.*;
import java.util.*;
import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The acceptance criteria for our client of a third party's authorization server: what the
 * upstream returns is the hostile corpus.
 *
 * <p>Three laws, in client mode. <b>Anything unexpected is refused</b>: every hostile
 * document or status ends in the framework's typed exception for its class (a 401 or 403
 * is {@code UnauthorizedException}, a 5xx or an unreadable document is
 * {@code ExternalServiceException}, a 4xx of the caller's making is the matching typed
 * exception), never a raw {@code IOException}, a {@code NullPointerException}, or a
 * half-built token. The mirror holds: documents carrying fields we do not read are
 * accepted. <b>Nothing the caller sent comes back</b>: the secret (whose value carries the
 * canary), the assertion, and the Basic header never appear in any message of the cause
 * chain. <b>The right type with a meaningful message</b>: the message names the step and the
 * URL it failed at.
 *
 * <p>Not driven here: a response that never arrives (the shared pool's response timeout is
 * minutes long) and a body of gigabytes (no bound, by decision: the same trust as the MCP
 * transport's own reads).
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
class McpTokenClientHostileUpstreamTest {
    static final String CANARY = "canary";
    static ScriptedAuthorizationServer server;
    static KeyPair keys;
    McpTokenClient client = new McpTokenClient();

    /** A hostile upstream, and the exception type the contract assigns it (null = accepted). */
    record Hostile(String name, Consumer<ScriptedAuthorizationServer> script, Class<? extends LLMReadableCheckedException> expected) {
    }

    @BeforeAll
    static void start() throws Exception {
        server = new ScriptedAuthorizationServer();
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
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
        server.prmResource = server.base + "/mcp";
        server.prmStatus = 200;
        server.prmRawBody = null;
        server.metadataIssuer = server.base;
        server.tokenEndpoint = server.base + "/token";
        server.metadataStatus = 200;
        server.metadataRawBody = null;
        server.grantTypes = List.of("client_credentials");
        server.authMethods = List.of("private_key_jwt", "client_secret_basic");
        server.tokenStatus = 200;
        server.tokenBody = null;
        server.tokenResetsConnection = false;
        server.probeStatus = 401;
        server.contentType = "application/json";
    }

    static String prm(String resource, String servers) {
        return "{\"resource\":" + resource + ",\"authorization_servers\":" + servers + "}";
    }

    static String metadata(String issuer, String tokenEndpoint, String extra) {
        return "{\"issuer\":" + issuer + ",\"token_endpoint\":" + tokenEndpoint + ",\"grant_types_supported\":[\"client_credentials\"],\"token_endpoint_auth_methods_supported\":[\"private_key_jwt\",\"client_secret_basic\"]" + extra + "}";
    }

    List<Hostile> corpus() {
        String base = "\"" + server.base + "\"";
        String mcp = "\"" + server.base + "/mcp\"";
        return List.of(
                // the protected resource document
                new Hostile("PRM is an HTML error page", s -> s.prmRawBody = "<html><body>" + CANARY + "</body></html>", ExternalServiceException.class),
                new Hostile("PRM is empty", s -> s.prmRawBody = "", ExternalServiceException.class),
                new Hostile("PRM is whitespace", s -> s.prmRawBody = "   \n", ExternalServiceException.class),
                new Hostile("PRM is null", s -> s.prmRawBody = "null", ExternalServiceException.class),
                new Hostile("PRM is an array", s -> s.prmRawBody = "[" + prm(mcp, "[" + base + "]") + "]", ExternalServiceException.class),
                new Hostile("PRM is a bare string", s -> s.prmRawBody = "\"" + CANARY + "\"", ExternalServiceException.class),
                new Hostile("PRM is truncated", s -> s.prmRawBody = prm(mcp, "[" + base).substring(0, 40), ExternalServiceException.class),
                new Hostile("PRM followed by garbage", s -> s.prmRawBody = prm(mcp, "[" + base + "]") + CANARY, ExternalServiceException.class),
                new Hostile("PRM naming its resource twice", s -> s.prmRawBody = "{\"resource\":\"http://" + CANARY + ".invalid/mcp\",\"resource\":" + mcp + ",\"authorization_servers\":[" + base + "]}", ExternalServiceException.class),
                new Hostile("PRM with a comment", s -> s.prmRawBody = "/* " + CANARY + " */" + prm(mcp, "[" + base + "]"), ExternalServiceException.class),
                new Hostile("PRM with a BOM", s -> s.prmRawBody = "﻿" + prm(mcp, "[" + base + "]"), ExternalServiceException.class),
                new Hostile("PRM names no resource", s -> s.prmRawBody = "{\"authorization_servers\":[" + base + "]}", ExternalServiceException.class),
                new Hostile("PRM whose resource is a number", s -> s.prmRawBody = prm("7", "[" + base + "]"), ExternalServiceException.class),
                new Hostile("PRM about another resource", s -> s.prmResource = s.base + "/" + CANARY, ExternalServiceException.class),
                new Hostile("PRM whose resource has no scheme", s -> s.prmResource = "mcp" + CANARY, ExternalServiceException.class),
                new Hostile("PRM whose resource does not parse as a URL", s -> s.prmResource = "::" + CANARY, ExternalServiceException.class),
                new Hostile("PRM whose resource is a javascript URL", s -> s.prmResource = "javascript:" + CANARY, ExternalServiceException.class),
                new Hostile("PRM answered 204 with no body", s -> s.prmStatus = 204, ExternalServiceException.class),
                new Hostile("PRM served as text/html (a valid document in the wrong media type)", s -> s.contentType = "text/html", ExternalServiceException.class),
                new Hostile("PRM served with a media type that does not parse", s -> s.contentType = CANARY, ExternalServiceException.class),
                new Hostile("PRM answered 302 carrying a valid document", s -> {
                    s.prmStatus = 302;
                    s.prmRawBody = prm(mcp, "[" + base + "]");
                }, ExternalServiceException.class),
                new Hostile("PRM about another host", s -> s.prmResource = "http://" + CANARY + ".invalid/mcp", ExternalServiceException.class),
                new Hostile("PRM names no authorization server", s -> s.prmRawBody = "{\"resource\":" + mcp + "}", ExternalServiceException.class),
                new Hostile("PRM with an empty server list", s -> s.prmRawBody = prm(mcp, "[]"), ExternalServiceException.class),
                new Hostile("PRM whose servers are a string", s -> s.prmRawBody = prm(mcp, base), ExternalServiceException.class),
                new Hostile("PRM whose server is a number", s -> s.prmRawBody = prm(mcp, "[7]"), ExternalServiceException.class),
                new Hostile("PRM whose server is a javascript URL", s -> s.prmRawBody = prm(mcp, "[\"javascript:" + CANARY + "\"]"), ExternalServiceException.class),
                new Hostile("PRM whose server is a file URL", s -> s.prmRawBody = prm(mcp, "[\"file:///" + CANARY + "\"]"), ExternalServiceException.class),
                new Hostile("PRM whose server is a bare word", s -> s.prmRawBody = prm(mcp, "[\"" + CANARY + "\"]"), ExternalServiceException.class),
                new Hostile("PRM whose server is a redirect", s -> s.prmRawBody = prm(mcp, "[\"" + server.base + "/redirect\"]"), ExternalServiceException.class),
                new Hostile("PRM answered 500", s -> s.prmStatus = 500, ExternalServiceException.class),
                new Hostile("PRM answered 403", s -> s.prmStatus = 403, UnauthorizedException.class),
                new Hostile("PRM answered 429", s -> s.prmStatus = 429, UpstreamThrottleException.class),
                // the authorization server document
                new Hostile("metadata is HTML", s -> s.metadataRawBody = "<html>" + CANARY + "</html>", ExternalServiceException.class),
                new Hostile("metadata names another issuer", s -> s.metadataIssuer = "http://" + CANARY + ".invalid", ExternalServiceException.class),
                new Hostile("metadata whose issuer has no scheme", s -> s.metadataIssuer = "as" + CANARY, ExternalServiceException.class),
                new Hostile("metadata whose issuer does not parse as a URL", s -> s.metadataIssuer = "::" + CANARY, ExternalServiceException.class),
                new Hostile("metadata answered 204 with no body", s -> s.metadataStatus = 204, ExternalServiceException.class),
                new Hostile("metadata names no issuer", s -> s.metadataRawBody = "{\"token_endpoint\":\"" + server.base + "/token\"}", ExternalServiceException.class),
                new Hostile("metadata names no token endpoint", s -> s.metadataRawBody = "{\"issuer\":" + base + "}", ExternalServiceException.class),
                new Hostile("metadata token endpoint on another host", s -> s.tokenEndpoint = "http://" + CANARY + ".invalid/token", ExternalServiceException.class),
                new Hostile("metadata token endpoint that is not http", s -> s.tokenEndpoint = "javascript:" + CANARY, ExternalServiceException.class),
                new Hostile("metadata token endpoint that is a number", s -> s.metadataRawBody = metadata(base, "7", ""), ExternalServiceException.class),
                new Hostile("metadata without the grant", s -> s.grantTypes = List.of("authorization_code"), ExternalServiceException.class),
                new Hostile("metadata without the method", s -> s.authMethods = List.of("client_secret_post"), ExternalServiceException.class),
                new Hostile("metadata omitting grant types (the RFC 8414 default has no client credentials)", s -> s.metadataRawBody = "{\"issuer\":" + base + ",\"token_endpoint\":\"" + server.base + "/token\"}", ExternalServiceException.class),
                new Hostile("metadata whose grant types are a string", s -> s.metadataRawBody = "{\"issuer\":" + base + ",\"token_endpoint\":\"" + server.base + "/token\",\"grant_types_supported\":\"client_credentials\"}", ExternalServiceException.class),
                new Hostile("metadata whose methods hold a number", s -> s.metadataRawBody = "{\"issuer\":" + base + ",\"token_endpoint\":\"" + server.base + "/token\",\"grant_types_supported\":[\"client_credentials\"],\"token_endpoint_auth_methods_supported\":[7]}", ExternalServiceException.class),
                new Hostile("metadata answered 500", s -> s.metadataStatus = 500, ExternalServiceException.class),
                new Hostile("metadata answered 404 at every form", s -> s.metadataStatus = 404, ExternalServiceException.class),
                // the token response
                new Hostile("token endpoint answers HTML with 200", s -> s.tokenBody = "<html>" + CANARY + "</html>", ExternalServiceException.class),
                new Hostile("token endpoint answers an error object with 200", s -> s.tokenBody = "{\"error\":\"" + CANARY + "\"}", ExternalServiceException.class),
                new Hostile("token that is a number", s -> s.tokenBody = "{\"access_token\":7,\"token_type\":\"Bearer\"}", ExternalServiceException.class),
                new Hostile("token that is empty", s -> s.tokenBody = "{\"access_token\":\"\",\"token_type\":\"Bearer\"}", ExternalServiceException.class),
                new Hostile("token carrying a header injection", s -> s.tokenBody = "{\"access_token\":\"t\\r\\nX-" + CANARY + ": 1\",\"token_type\":\"Bearer\"}", ExternalServiceException.class),
                new Hostile("token with a space", s -> s.tokenBody = "{\"access_token\":\"t " + CANARY + "\",\"token_type\":\"Bearer\"}", ExternalServiceException.class),
                new Hostile("token outside the bearer alphabet", s -> s.tokenBody = "{\"access_token\":\"t\\u00e9" + CANARY + "\",\"token_type\":\"Bearer\"}", ExternalServiceException.class),
                new Hostile("token without a type", s -> s.tokenBody = "{\"access_token\":\"t\"}", ExternalServiceException.class),
                new Hostile("token of another type", s -> s.tokenBody = "{\"access_token\":\"t\",\"token_type\":\"MAC\"}", ExternalServiceException.class),
                new Hostile("expires_in negative", s -> s.tokenBody = "{\"access_token\":\"t\",\"token_type\":\"Bearer\",\"expires_in\":-1}", ExternalServiceException.class),
                new Hostile("expires_in null", s -> s.tokenBody = "{\"access_token\":\"t\",\"token_type\":\"Bearer\",\"expires_in\":null}", ExternalServiceException.class),
                new Hostile("expires_in a string", s -> s.tokenBody = "{\"access_token\":\"t\",\"token_type\":\"Bearer\",\"expires_in\":\"" + CANARY + "\"}", ExternalServiceException.class),
                new Hostile("expires_in a float", s -> s.tokenBody = "{\"access_token\":\"t\",\"token_type\":\"Bearer\",\"expires_in\":1.5}", ExternalServiceException.class),
                new Hostile("expires_in past any integer", s -> s.tokenBody = "{\"access_token\":\"t\",\"token_type\":\"Bearer\",\"expires_in\":1e400}", ExternalServiceException.class),
                new Hostile("expires_in past a long (2^64, whose low bits are zero)", s -> s.tokenBody = "{\"access_token\":\"t\",\"token_type\":\"Bearer\",\"expires_in\":18446744073709551616}", ExternalServiceException.class),
                new Hostile("expires_in at the largest long (past any instant)", s -> s.tokenBody = "{\"access_token\":\"t\",\"token_type\":\"Bearer\",\"expires_in\":9223372036854775807}", ExternalServiceException.class),
                new Hostile("token endpoint answers 302 carrying a token", s -> {
                    s.tokenStatus = 302;
                    s.tokenBody = "{\"access_token\":\"t\",\"token_type\":\"Bearer\"}";
                }, ExternalServiceException.class),
                new Hostile("token endpoint answers 204 with no body", s -> s.tokenStatus = 204, ExternalServiceException.class),
                new Hostile("token endpoint answers 400", s -> s.tokenStatus = 400, InvalidInputException.class),
                new Hostile("token endpoint answers 401", s -> s.tokenStatus = 401, UnauthorizedException.class),
                new Hostile("token endpoint answers 403", s -> s.tokenStatus = 403, UnauthorizedException.class),
                new Hostile("token endpoint answers 404", s -> s.tokenStatus = 404, ResourceNotFoundException.class),
                new Hostile("token endpoint answers 413", s -> s.tokenStatus = 413, InvalidInputException.class),
                new Hostile("token endpoint answers 429", s -> s.tokenStatus = 429, UpstreamThrottleException.class),
                new Hostile("token endpoint answers 500", s -> s.tokenStatus = 500, ExternalServiceException.class),
                new Hostile("token endpoint answers 502", s -> s.tokenStatus = 502, ExternalServiceException.class),
                new Hostile("token endpoint answers 503", s -> s.tokenStatus = 503, ExternalServiceException.class),
                new Hostile("token endpoint redirects", s -> s.tokenEndpoint = server.base + "/redirect", ExternalServiceException.class),
                new Hostile("token endpoint closes the connection", s -> s.tokenResetsConnection = true, ExternalServiceException.class),
                // the probe
                new Hostile("no PRM and the server needs no auth", s -> {
                    s.servePrmAtWellKnown = false;
                    s.probeStatus = 200;
                }, ExternalServiceException.class),
                new Hostile("no PRM and the server is down", s -> {
                    s.servePrmAtWellKnown = false;
                    s.probeStatus = 503;
                }, ExternalServiceException.class),
                new Hostile("no PRM and a challenge naming none", s -> s.servePrmAtWellKnown = false, UnauthorizedException.class),
                new Hostile("no PRM and a challenge naming something that is not a URL", s -> {
                    s.servePrmAtWellKnown = false;
                    s.challengeMetadataUrl = "::" + CANARY;
                }, ExternalServiceException.class),
                // The challenge may name any http(s) host; this one does not resolve, which is the transport's failure.
                new Hostile("no PRM and a challenge naming a host that does not resolve", s -> {
                    s.servePrmAtWellKnown = false;
                    s.challengeMetadataUrl = "http://" + CANARY + ".invalid/prm";
                }, ExternalServiceException.class),
                new Hostile("no PRM and a challenge naming a file URL", s -> {
                    s.servePrmAtWellKnown = false;
                    s.challengeMetadataUrl = "file:///etc/" + CANARY;
                }, ExternalServiceException.class),
                new Hostile("no PRM and a challenge naming a relative URL", s -> {
                    s.servePrmAtWellKnown = false;
                    s.challengeMetadataUrl = "/prm-elsewhere";
                }, ExternalServiceException.class),
                // the mirror: accepted
                new Hostile("PRM and metadata carrying fields we do not read", s -> {
                    s.prmRawBody = "{\"resource\":" + mcp + ",\"authorization_servers\":[" + base + "],\"scopes_supported\":[\"x\"],\"" + CANARY + "\":\"" + CANARY + "\"}";
                    s.metadataRawBody = metadata(base, "\"" + server.base + "/token\"", ",\"" + CANARY + "\":[\"" + CANARY + "\"],\"response_types_supported\":[\"code\"]");
                }, null),
                new Hostile("a token response with extra fields and no expiry", s -> s.tokenBody = "{\"access_token\":\"t\",\"token_type\":\"bearer\",\"" + CANARY + "\":1}", null));
    }

    /** Both credential kinds: the secret, whose value carries the canary, and a signing key, whose assertion is the other thing that must never be quoted. */
    static List<McpClientCredential> credentials() {
        return List.of(
                new AgentSecret(ScriptedAuthorizationServer.USR, ScriptedAuthorizationServer.SECRET),
                new AgentSigningKey(ScriptedAuthorizationServer.USR, keys.getPrivate(), null));
    }

    Throwable run(Hostile hostile, McpClientCredential credential) {
        reset();
        hostile.script().accept(server);
        try {
            client.acquire(server.mcpUrl(), credential);
            return null;
        }
        catch (Throwable t) {
            return t;
        }
    }

    /** The assertion parts that were posted, so a message can be checked for any of them. */
    List<String> assertionsPosted() {
        List<String> parts = new ArrayList<>();
        for (Map<String, String> request : server.tokenRequests) {
            String assertion = request.get("client_assertion");
            if (assertion != null) {
                parts.add(assertion);
                parts.add(assertion.substring(assertion.lastIndexOf('.') + 1));
            }
        }
        return parts;
    }

    @TestFactory
    List<DynamicTest> everyHostileUpstreamEndsInTheTypedException() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Hostile hostile : corpus()) {
            for (McpClientCredential credential : credentials()) {
                tests.add(DynamicTest.dynamicTest(hostile.name() + " [" + credential.tokenEndpointAuthMethod() + "]", () -> {
                    Throwable outcome = run(hostile, credential);
                    if (hostile.expected() == null) {
                        assertNull(outcome, "the contract accepts this: " + outcome);
                    }
                    else {
                        assertNotNull(outcome, "the contract refuses this");
                        assertInstanceOf(hostile.expected(), outcome, "the typed exception for this class of failure: " + outcome);
                    }
                }));
            }
        }
        return tests;
    }

    @TestFactory
    List<DynamicTest> noFailureQuotesTheCredential() {
        List<DynamicTest> tests = new ArrayList<>();
        String basic = Base64.getEncoder().encodeToString((ScriptedAuthorizationServer.USR + ":" + ScriptedAuthorizationServer.SECRET).getBytes());
        for (Hostile hostile : corpus()) {
            for (McpClientCredential credential : credentials()) {
                tests.add(DynamicTest.dynamicTest(hostile.name() + " [" + credential.tokenEndpointAuthMethod() + "]", () -> {
                    Throwable outcome = run(hostile, credential);
                    List<String> assertions = assertionsPosted();
                    for (Throwable t = outcome; t != null; t = t.getCause()) {
                        String message = String.valueOf(t.getMessage());
                        assertFalse(message.contains(ScriptedAuthorizationServer.SECRET), "the secret is in no message: " + message);
                        assertFalse(message.contains(basic), "the Basic header is in no message: " + message);
                        for (String part : assertions) {
                            assertFalse(message.contains(part), "the assertion is in no message: " + message);
                        }
                        if (t instanceof LLMReadable readable) {
                            String llm = readable.getLLMMessage();
                            assertFalse(llm.contains(ScriptedAuthorizationServer.SECRET), "nor in what the model reads");
                            for (String part : assertions) {
                                assertFalse(llm.contains(part), "nor the assertion in what the model reads");
                            }
                        }
                    }
                }));
            }
        }
        return tests;
    }

    @TestFactory
    List<DynamicTest> everyRefusalNamesTheUrlItFailedAt() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Hostile hostile : corpus()) {
            if (hostile.expected() == null) {
                continue;
            }
            tests.add(DynamicTest.dynamicTest(hostile.name(), () -> {
                Throwable outcome = run(hostile, credentials().get(0));
                String message = ((LLMReadable)outcome).getLLMMessage();
                assertTrue(message.contains("http://"), "the message names the URL it failed at: " + message);
            }));
        }
        return tests;
    }
}
