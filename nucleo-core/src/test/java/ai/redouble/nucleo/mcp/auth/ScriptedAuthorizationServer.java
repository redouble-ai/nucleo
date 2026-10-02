/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.auth;

import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * One in-process server playing both halves of the flow the way the conformance referee
 * does: the MCP server (bearer-guarded JSON-RPC at {@code /mcp}, with the protected
 * resource metadata at the path-inserted well-known form) and the authorization server
 * (metadata at the well-known form, the token endpoint verifying an ES256/RS256 assertion
 * against a public key it holds or a Basic secret it knows). Every behaviour a test needs
 * to vary is a field.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
final class ScriptedAuthorizationServer {
    static final String USR = "conformance-test-client";
    static final String SECRET = "rda_conformance-secret-canary";
    static final String JWT_BEARER = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";
    final HttpServer server;
    final String base;
    /** The public key assertions must verify with. */
    volatile PublicKey publicKey;
    volatile boolean servePrmAtWellKnown = true;
    volatile String challengeMetadataUrl;
    volatile String prmResource;
    volatile String metadataIssuer;
    volatile String tokenEndpoint;
    volatile List<String> grantTypes = List.of("client_credentials");
    volatile List<String> authMethods = List.of("private_key_jwt", "client_secret_basic");
    volatile int tokenStatus = 200;
    volatile String tokenBody;
    volatile long expiresIn = 3600;
    volatile int probeStatus = 401;
    volatile boolean rejectNextBearer;
    /** Raw document overrides: the status and body served verbatim when set. */
    volatile int prmStatus = 200;
    volatile String prmRawBody;
    volatile int metadataStatus = 200;
    volatile String metadataRawBody;
    /** When set, the token endpoint closes the connection without answering. */
    volatile boolean tokenResetsConnection;
    /** The media type every answer is served as. */
    volatile String contentType = "application/json";
    final Set<String> minted = ConcurrentHashMap.newKeySet();
    final List<Map<String, String>> tokenRequests = new CopyOnWriteArrayList<>();
    final List<String> tokenAuthorizationHeaders = new CopyOnWriteArrayList<>();
    final List<String> mcpBearers = new CopyOnWriteArrayList<>();
    final AtomicInteger mints = new AtomicInteger();

    ScriptedAuthorizationServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        prmResource = base + "/mcp";
        metadataIssuer = base;
        tokenEndpoint = base + "/token";
        server.createContext("/.well-known/oauth-protected-resource/mcp", this::protectedResource);
        server.createContext("/prm-elsewhere", this::protectedResource);
        server.createContext("/.well-known/oauth-authorization-server", this::metadata);
        server.createContext("/token", this::token);
        server.createContext("/mcp", this::mcp);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://other.invalid/");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/mcp-with-challenge", exchange -> {
            exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer resource_metadata=\"" + challengeMetadataUrl + "\"");
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();
    }

    void stop() {
        server.stop(0);
    }

    URI mcpUrl() {
        return URI.create(base + "/mcp");
    }

    private void protectedResource(HttpExchange exchange) throws IOException {
        boolean wellKnown = exchange.getRequestURI().getPath().startsWith("/.well-known");
        if (wellKnown && !servePrmAtWellKnown) {
            respond(exchange, 404, "{}");
            return;
        }
        if (prmRawBody != null || prmStatus != 200) {
            respond(exchange, prmStatus, prmRawBody == null ? "" : prmRawBody);
            return;
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("resource", prmResource);
        document.put("authorization_servers", List.of(base));
        respond(exchange, 200, NucleoJsonSerializer.write(document));
    }

    private void metadata(HttpExchange exchange) throws IOException {
        if (metadataRawBody != null || metadataStatus != 200) {
            respond(exchange, metadataStatus, metadataRawBody == null ? "" : metadataRawBody);
            return;
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("issuer", metadataIssuer);
        document.put("token_endpoint", tokenEndpoint);
        document.put("response_types_supported", List.of());
        document.put("grant_types_supported", grantTypes);
        document.put("token_endpoint_auth_methods_supported", authMethods);
        document.put("token_endpoint_auth_signing_alg_values_supported", List.of("RS256", "ES256"));
        respond(exchange, 200, NucleoJsonSerializer.write(document));
    }

    private void token(HttpExchange exchange) throws IOException {
        Map<String, String> form = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        tokenRequests.add(form);
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        tokenAuthorizationHeaders.add(authorization);
        if (tokenResetsConnection) {
            exchange.close();
            return;
        }
        if (tokenStatus != 200) {
            respond(exchange, tokenStatus, tokenBody == null ? "{\"error\":\"invalid_client\"}" : tokenBody);
            return;
        }
        boolean authenticated;
        if (form.containsKey("client_assertion")) {
            authenticated = JWT_BEARER.equals(form.get("client_assertion_type")) && assertionVerifies(form.get("client_assertion"));
        }
        else {
            authenticated = authorization != null && authorization.equals("Basic " + Base64.getEncoder().encodeToString((USR + ":" + SECRET).getBytes(StandardCharsets.UTF_8)));
        }
        if (!authenticated) {
            respond(exchange, 401, "{\"error\":\"invalid_client\"}");
            return;
        }
        if (tokenBody != null) {
            respond(exchange, 200, tokenBody);
            return;
        }
        String token = "cc-token-" + mints.incrementAndGet();
        minted.add(token);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", token);
        body.put("token_type", "Bearer");
        body.put("expires_in", expiresIn);
        respond(exchange, 200, NucleoJsonSerializer.write(body));
    }

    /** The referee's checks: signature, aud = issuer (with or without slash), iss = sub = client id. */
    private boolean assertionVerifies(String compact) {
        try {
            String[] parts = compact.split("\\.");
            JsonNode header = NucleoJsonSerializer.readTree(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
            JsonNode claims = NucleoJsonSerializer.readTree(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            String algorithm = switch (header.get("alg").textValue()) {
                case "RS256" -> "SHA256withRSA";
                case "ES256" -> "SHA256withECDSAinP1363Format";
                default -> throw new IllegalArgumentException("unexpected alg");
            };
            Signature s = Signature.getInstance(algorithm);
            s.initVerify(publicKey);
            s.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            String aud = claims.get("aud").textValue();
            return s.verify(Base64.getUrlDecoder().decode(parts[2]))
                    && (aud.equals(base) || aud.equals(base + "/"))
                    && USR.equals(claims.get("iss").textValue())
                    && USR.equals(claims.get("sub").textValue())
                    && claims.get("exp").longValue() > System.currentTimeMillis() / 1000;
        }
        catch (Exception e) {
            return false;
        }
    }

    private void mcp(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "");
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        String bearer = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
        mcpBearers.add(bearer);
        if (bearer == null) {
            if (probeStatus != 401) {
                respond(exchange, probeStatus, "{}");
                return;
            }
            String challenge = "Bearer error=\"invalid_token\"" + (challengeMetadataUrl == null ? "" : ", resource_metadata=\"" + challengeMetadataUrl + "\"");
            exchange.getResponseHeaders().add("WWW-Authenticate", challenge);
            respond(exchange, 401, "");
            return;
        }
        if (rejectNextBearer || !minted.contains(bearer)) {
            rejectNextBearer = false;
            exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer error=\"invalid_token\"");
            respond(exchange, 401, "");
            return;
        }
        JsonNode request = NucleoJsonSerializer.readTree(body);
        String method = request.path("method").textValue();
        if (!request.has("id")) {
            respond(exchange, 202, "");
            return;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        switch (method) {
            case "initialize" -> {
                result.put("protocolVersion", request.path("params").path("protocolVersion").textValue());
                result.put("capabilities", Map.of("tools", Map.of()));
                result.put("serverInfo", Map.of("name", "scripted", "version", "0"));
            }
            case "tools/list" -> result.put("tools", List.of(Map.of(
                    "name", "scripted_ping",
                    "description", "answers",
                    "inputSchema", Map.of("type", "object", "properties", Map.of()))));
            default -> {
                respond(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + request.get("id") + ",\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}");
                return;
            }
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", request.get("id").isTextual() ? request.get("id").textValue() : request.get("id").numberValue());
        response.put("result", result);
        respond(exchange, 200, NucleoJsonSerializer.write(response));
    }

    private static Map<String, String> form(String body) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            values.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return values;
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }
}
