/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How a tool call's failure comes back typed. A result the server marks {@code isError}
 * is the tool complaining about its arguments, so it surfaces as {@link InvalidInputException}
 * carrying the result's text; a protocol failure with no typed cause surfaces as
 * {@link ExternalServiceException} naming {@code MCP:} plus the endpoint id.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-18)
 */
class GenericMCPClientResultMappingTest {
    static HttpServer server;
    static String url;

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
        server.createContext("/mcp", GenericMCPClientResultMappingTest::mcp);
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    static HTTPMCPEndpoint endpoint() {
        HTTPMCPEndpoint endpoint = new HTTPMCPEndpoint();
        endpoint.setUrl(url);
        endpoint.setFlavor(HTTPMCPEndpoint.Flavor.STREAMABLE_HTTP);
        return endpoint;
    }

    @Test
    void anIsErrorResultIsTheToolComplainingAboutItsArguments() throws Exception {
        try (GenericMCPClient client = GenericMCPClient.connect(endpoint())) {
            InvalidInputException complaint = assertThrows(InvalidInputException.class,
                    () -> client.callTool("flaky", NucleoJsonSerializer.valueToTree(Map.of("flag", "yes"))),
                    "a result marked isError surfaces as the correctable input complaint it is");
            assertTrue(complaint.getMessage().contains("flaky"), "the complaint names the tool");
            assertTrue(complaint.getMessage().contains("the flag must be a boolean"),
                    "the complaint carries the result's text so the caller can correct the call");
        }
    }

    @Test
    void aProtocolFailureIsAnExternalFailureNamingTheEndpoint() throws Exception {
        HTTPMCPEndpoint endpoint = endpoint();
        try (GenericMCPClient client = GenericMCPClient.connect(endpoint)) {
            ExternalServiceException failure = assertThrows(ExternalServiceException.class,
                    () -> client.callTool("absent", NucleoJsonSerializer.valueToTree(Map.of())),
                    "a JSON-RPC error with no typed cause is the service failing");
            assertEquals("MCP:" + endpoint.getEndpointId(), failure.getServiceName(),
                    "the failure names the endpoint it came from");
        }
    }

    /** Unauthenticated JSON-RPC: initialize, tools/list, and a tools/call that answers isError for 'flaky' and a JSON-RPC error otherwise. */
    private static void mcp(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode request = NucleoJsonSerializer.readTree(body);
        if (!request.has("id")) {
            respond(exchange, 202, "");
            return;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        switch (request.path("method").textValue()) {
            case "initialize" -> {
                result.put("protocolVersion", request.path("params").path("protocolVersion").textValue());
                result.put("capabilities", Map.of("tools", Map.of()));
                result.put("serverInfo", Map.of("name", "mapping", "version", "0"));
            }
            case "tools/list" -> result.put("tools", List.of(Map.of(
                    "name", "flaky",
                    "description", "complains",
                    "inputSchema", Map.of("type", "object", "properties", Map.of()))));
            case "tools/call" -> {
                if (!"flaky".equals(request.path("params").path("name").textValue())) {
                    respond(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + request.get("id") + ",\"error\":{\"code\":-32602,\"message\":\"unknown tool\"}}");
                    return;
                }
                result.put("content", List.of(Map.of("type", "text", "text", "the flag must be a boolean")));
                result.put("isError", true);
            }
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

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }
}
