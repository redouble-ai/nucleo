/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.mcp.auth.*;
import com.fasterxml.jackson.databind.*;
import io.modelcontextprotocol.client.transport.*;
import io.modelcontextprotocol.client.transport.customizer.*;
import io.modelcontextprotocol.json.jackson2.*;
import io.modelcontextprotocol.spec.*;

import java.net.*;
import java.net.http.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;

/**
 * HTTP transport endpoint. Supports two flavors:
 * <ul>
 *   <li>{@link Flavor#SSE} - the original HTTP+SSE transport (default for backward compatibility).</li>
 *   <li>{@link Flavor#STREAMABLE_HTTP} - the newer streamable HTTP transport from MCP SDK 1.1.</li>
 * </ul>
 *
 * <p>Custom headers are applied via the SDK's {@code httpRequestCustomizer} hook on the
 * underlying HttpRequest.Builder, so they ride every JSON-RPC request issued by the
 * transport.
 *
 * <p>A {@link McpClientCredential} makes the endpoint an OAuth client of the server's
 * authorization server: a {@link McpTokenSupplier} per transport mints a bearer token
 * through the specification's discovery and client-credentials flow and attaches it to
 * every request, and the SDK's authorization error handler forgets a token the server
 * rejected. Only the streamable transport offers that handler, so a credential on the SSE
 * flavor is refused: a rejected token there would be ridden to expiry. A credential and an
 * {@code Authorization} header on one endpoint contradict each other and are refused too.
 *
 * <p>The endpoint id folds in the credential's agent and a digest of the headers: two
 * endpoints to one URL under different identities are different clients to
 * {@link MCPClientPool}, so one consumer never rides another's session.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-05-08)
 */
public class HTTPMCPEndpoint implements MCPEndpoint {
    /**
     * HTTP transport flavor. Match the server's advertised transport.
     */
    public enum Flavor {
        SSE,
        STREAMABLE_HTTP
    }

    private static final String AUTHORIZATION = "Authorization";
    private String url;
    private Map<String, String> headers;
    private Flavor flavor = Flavor.SSE;
    private McpClientCredential credential;

    public HTTPMCPEndpoint() {
    }

    @Override
    public MCPTransportType getTransportType() {
        return MCPTransportType.HTTP;
    }

    @Override
    public String getEndpointId() {
        StringBuilder id = new StringBuilder(url);
        if (credential != null) {
            id.append(" #as:").append(credential.usr());
        }
        if (headers != null && !headers.isEmpty()) {
            id.append(" #headers:").append(headersDigest());
        }
        return id.toString();
    }

    /**
     * A digest rather than the headers themselves: they commonly carry an API key, and
     * the id is logged.
     */
    private String headersDigest() {
        StringBuilder canonical = new StringBuilder();
        for (String key : new TreeSet<>(headers.keySet())) {
            canonical.append(key).append('=').append(headers.get(key)).append('\n');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a mandatory JDK algorithm", e);
        }
    }

    @Override
    public McpClientTransport createTransport(ObjectMapper objectMapper) {
        JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(objectMapper);
        if (credential != null && headers != null && headers.keySet().stream().anyMatch(AUTHORIZATION::equalsIgnoreCase)) {
            throw new IllegalStateException("The endpoint " + url + " has both a credential and an " + AUTHORIZATION + " header; one identity per endpoint");
        }
        return switch (flavor) {
            case SSE -> {
                if (credential != null) {
                    throw new IllegalStateException("The endpoint " + url + " has a credential on the SSE flavor; only the streamable transport can drop a rejected token");
                }
                HttpClientSseClientTransport.Builder b = HttpClientSseClientTransport.builder(url)
                        .jsonMapper(jsonMapper);
                if (headers != null && !headers.isEmpty()) {
                    b.httpRequestCustomizer((req, method, uri, body, context) -> headers.forEach(req::header));
                }
                yield b.build();
            }
            case STREAMABLE_HTTP -> {
                HttpClientStreamableHttpTransport.Builder b = HttpClientStreamableHttpTransport.builder(url)
                        .jsonMapper(jsonMapper);
                if (credential != null) {
                    McpTokenSupplier supplier = new McpTokenSupplier(URI.create(url), credential, new McpTokenClient());
                    b.httpRequestCustomizer((req, method, uri, body, context) -> {
                        if (headers != null) {
                            headers.forEach(req::header);
                        }
                        attach(supplier, req);
                    });
                    b.authorizationErrorHandler(McpHttpClientTransportAuthorizationErrorHandler.fromSync((snapshot, response, context) -> {
                        supplier.invalidate();
                        return false;
                    }));
                }
                else if (headers != null && !headers.isEmpty()) {
                    b.httpRequestCustomizer((req, method, uri, body, context) -> headers.forEach(req::header));
                }
                yield b.build();
            }
        };
    }

    /**
     * The customizer's contract has no checked exceptions; a mint that fails surfaces as the
     * framework's unchecked LLM-readable exception, which the client unwraps to the typed
     * one the flow threw.
     */
    private static void attach(McpTokenSupplier supplier, HttpRequest.Builder request) {
        try {
            supplier.attach(request);
        }
        catch (LLMReadableCheckedException e) {
            throw new UncorrectableRuntimeLLMException(e.getLLMMessage(), e);
        }
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }

    public Flavor getFlavor() {
        return flavor;
    }

    public void setFlavor(Flavor flavor) {
        this.flavor = flavor;
    }

    public McpClientCredential getCredential() {
        return credential;
    }

    /**
     * The agent identity this endpoint connects as; null means the server needs none or
     * the headers carry it.
     */
    public void setCredential(McpClientCredential credential) {
        this.credential = credential;
    }
}
