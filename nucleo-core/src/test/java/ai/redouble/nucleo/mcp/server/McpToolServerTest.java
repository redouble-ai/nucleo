/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.server;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.mcp.*;
import ai.redouble.nucleo.mcp.server.fixtures.*;
import io.modelcontextprotocol.common.*;
import io.modelcontextprotocol.server.*;
import io.modelcontextprotocol.spec.*;
import org.junit.jupiter.api.*;
import reactor.core.publisher.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The serving path end to end, in process: the real SDK server behind the real
 * interposer, tools run by the real dispatcher. The transport is captured so the test
 * drives the installed handler with JSON-RPC requests directly.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-02)
 */
public class McpToolServerTest {
    static final String PRINCIPAL = "principal";
    static final String ALICE = "alice";
    static final String BOB = "bob";
    /** Granted nothing by name; everything she may use comes through her group. */
    static final String CAROL = "carol";
    static final String PINGERS = "pingers";
    static final Duration CALL_TIMEOUT = Duration.ofSeconds(2);
    /** Granted the one tool with an optional property, for the dialect cases. */
    static final String DAVE = "dave";
    /** Keyed by a consumer name or a group name; a consumer gets the union. */
    static final Map<String, Set<String>> GRANTS = Map.of(
            ALICE, Set.of("mcp_echo", "mcp_ping", "mcp_faulty", "mcp_slow_parent", "mcp_citing", "mcp_tree"),
            BOB, Set.of("mcp_ping"),
            PINGERS, Set.of("mcp_ping"),
            DAVE, Set.of("mcp_strict"));
    static final Map<String, Set<String>> GROUPS = Map.of(CAROL, Set.of(PINGERS));
    static CapturingTransport transport;
    static McpToolServer server;
    static final AtomicInteger ids = new AtomicInteger();

    /**
     * Hands the installed handler to the test instead of a wire.
     */
    static class CapturingTransport implements McpStatelessServerTransport {
        volatile McpStatelessServerHandler handler;
        final AtomicBoolean closed = new AtomicBoolean(false);

        @Override
        public void setMcpHandler(McpStatelessServerHandler handler) {
            this.handler = handler;
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.fromRunnable(() -> closed.set(true));
        }
    }

    @BeforeAll
    static void startServer() {
        JobDispatcher.getInstance().start();
        transport = new CapturingTransport();
        server = new McpToolServer();
        server.setScanPackages(List.of(McpToolCatalogTest.FIXTURES));
        server.setProviders(List.of());
        server.setConsumerResolver(context -> context.get(PRINCIPAL) instanceof String name
                ? new McpConsumer(name, GROUPS.getOrDefault(name, Set.of()))
                : null);
        server.setAccessPolicy((consumer, provider) -> {
            if (GRANTS.getOrDefault(consumer.name(), Set.of()).contains(provider.name())) {
                return true;
            }
            for (String group : consumer.groups()) {
                if (GRANTS.getOrDefault(group, Set.of()).contains(provider.name())) {
                    return true;
                }
            }
            return false;
        });
        server.setServerName("test-server");
        server.setServerVersion("0");
        server.setCallTimeout(CALL_TIMEOUT);
        server.setTransport(transport);
        server.start();
        assertNotNull(transport.handler, "the SDK server installs its handler through the interposer");
    }

    @AfterAll
    static void closeServer() {
        server.close();
        assertTrue(transport.closed.get(), "closing the server closes the host transport");
    }

    // ---- request helpers ----

    static McpTransportContext as(String consumer) {
        return McpTransportContext.create(Map.of(PRINCIPAL, consumer));
    }

    static McpSchema.JSONRPCRequest request(String method, Object params) {
        return new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, method, ids.incrementAndGet(), params);
    }

    static McpSchema.JSONRPCResponse send(McpTransportContext context, String method, Object params) {
        return transport.handler.handleRequest(context, request(method, params)).block();
    }

    static List<String> listedNames(McpTransportContext context) {
        McpSchema.JSONRPCResponse response = send(context, McpSchema.METHOD_TOOLS_LIST, Map.of());
        assertNull(response.error());
        McpSchema.ListToolsResult listing = (McpSchema.ListToolsResult)response.result();
        return listing.tools().stream().map(McpSchema.Tool::name).sorted().toList();
    }

    static McpSchema.CallToolResult call(McpTransportContext context, String tool, Map<String, Object> arguments) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", tool);
        if (arguments != null) {
            params.put("arguments", arguments);
        }
        McpSchema.JSONRPCResponse response = send(context, McpSchema.METHOD_TOOLS_CALL, params);
        assertNull(response.error(), () -> "call of " + tool + " answered a JSON-RPC error: " + response.error());
        return (McpSchema.CallToolResult)response.result();
    }

    static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent)result.content().get(0)).text();
    }

    // ---- listing ----

    // ---- dialects: one contract, selected per request ----

    /** Params carrying the optional dialect selector, the way a caller sends it. */
    static Map<String, Object> inDialect(SchemaDialect dialect, Map<String, Object> params) {
        Map<String, Object> named = new LinkedHashMap<>(params);
        named.put("_meta", Map.of(SchemaDialect.META_KEY, dialect.wireName()));
        return named;
    }

    static McpSchema.Tool listedTool(McpTransportContext context, Map<String, Object> params, String name) {
        McpSchema.ListToolsResult listing = (McpSchema.ListToolsResult)send(context, McpSchema.METHOD_TOOLS_LIST, params).result();
        return listing.tools().stream().filter(tool -> tool.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void eachDialectPublishesItsOwnSpellingOfTheSameTool() {
        McpSchema.Tool canonical = listedTool(as(DAVE), Map.of(), "mcp_strict");
        McpSchema.Tool strict = listedTool(as(DAVE), inDialect(SchemaDialect.OPENAI_STRICT, Map.of()), "mcp_strict");
        List<?> canonicalRequired = (List<?>)canonical.inputSchema().get("required");
        List<?> strictRequired = (List<?>)strict.inputSchema().get("required");
        assertEquals(List.of("text"), canonicalRequired, "the canonical form says what is really required");
        assertTrue(strictRequired.containsAll(List.of("text", "count", "nested")),
                "the strict spelling requires every property, as OpenAI demands: " + strictRequired);
        assertEquals(canonical.description(), strict.description(), "same tool, same contract");
    }

    @Test
    void aStrictRequestAcceptsTheNullsItsSpellingForcesAndACanonicalOneRefusesThem() {
        // A strict caller sends every property, the optional ones as null: that is the whole
        // point of the spelling, and the request's dialect is what makes it legal.
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("text", "hello");
        for (String optional : McpDialectHostileInputTest.OPTIONALS) {
            arguments.put(optional, null);
        }
        Map<String, Object> params = Map.of("name", "mcp_strict", "arguments", arguments);
        McpSchema.CallToolResult viaStrict = (McpSchema.CallToolResult)send(as(DAVE), McpSchema.METHOD_TOOLS_CALL,
                inDialect(SchemaDialect.OPENAI_STRICT, params)).result();
        assertNotEquals(Boolean.TRUE, viaStrict.isError(), "a strict caller spells an omitted optional as null: " + viaStrict);
        McpSchema.CallToolResult viaCanonical = (McpSchema.CallToolResult)send(as(DAVE), McpSchema.METHOD_TOOLS_CALL, params).result();
        assertEquals(Boolean.TRUE, viaCanonical.isError(), "a canonical caller sent a null where an integer is declared");
        assertEquals(true, viaCanonical.meta().get(McpToolServer.META_CORRECTABLE));
    }

    @Test
    void theDialectMayAlsoArriveOnTheTransportContext() {
        // The second door into the same slot: a host that forwards a query parameter puts
        // the raw value on the context under the same key, for a client whose only
        // configurable surface is its URL. The request's own naming wins over it.
        McpTransportContext viaQuery = McpTransportContext.create(Map.of(PRINCIPAL, DAVE,
                SchemaDialect.META_KEY, SchemaDialect.OPENAI_STRICT.wireName()));
        McpSchema.Tool strict = listedTool(viaQuery, Map.of(), "mcp_strict");
        assertTrue(((List<?>)strict.inputSchema().get("required")).contains("count"),
                "the context named the strict spelling: " + strict.inputSchema().get("required"));
        McpSchema.Tool overridden = listedTool(viaQuery, inDialect(SchemaDialect.CANONICAL, Map.of()), "mcp_strict");
        assertEquals(List.of("text"), overridden.inputSchema().get("required"),
                "the request's own naming wins over the transport's");
    }

    @Test
    void initializeNamesTheParameterAndEveryDialect() {
        Map<String, Object> params = Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(),
                "clientInfo", Map.of("name", "test-client", "version", "0"));
        McpSchema.InitializeResult initialized = (McpSchema.InitializeResult)send(as(DAVE), McpSchema.METHOD_INITIALIZE, params).result();
        assertTrue(initialized.instructions().contains(SchemaDialect.META_KEY), initialized.instructions());
        assertTrue(initialized.instructions().contains(SchemaDialect.OPENAI_STRICT.wireName()), initialized.instructions());
    }

    @Test
    void listingIsFilteredPerConsumer() {
        assertEquals(List.of("mcp_citing", "mcp_echo", "mcp_faulty", "mcp_ping", "mcp_slow_parent", "mcp_tree"), listedNames(as(ALICE)));
        assertEquals(List.of("mcp_ping"), listedNames(as(BOB)));
        assertEquals(List.of(), listedNames(as("nobody")));
    }

    @Test
    void groupMembershipGrantsLikeTheName() {
        assertEquals(List.of("mcp_ping"), listedNames(as(CAROL)), "a grant keyed by a group reaches its members");
        McpSchema.CallToolResult result = call(as(CAROL), "mcp_ping", Map.of());
        assertFalse(Boolean.TRUE.equals(result.isError()), "a group-granted call runs");
        McpSchema.JSONRPCResponse ungranted = send(as(CAROL), McpSchema.METHOD_TOOLS_CALL, Map.of("name", "mcp_echo"));
        assertNotNull(ungranted.error(), "the group's grant does not widen beyond its list");
    }

    @Test
    void unauthenticatedListsNothing() {
        assertEquals(List.of(), listedNames(McpTransportContext.EMPTY));
        assertEquals(List.of(), listedNames(as("")));
    }

    @Test
    void unknownMethodIsRefusedWithoutDelegation() {
        McpSchema.JSONRPCResponse response = send(as(ALICE), "resources/list", Map.of());
        assertNotNull(response.error());
        assertEquals(McpSchema.ErrorCodes.METHOD_NOT_FOUND, response.error().code());
        assertNull(response.result());
    }

    // ---- admission and parity ----

    @Test
    void ungrantedAndNonexistentToolsAnswerIdentically() {
        // Probe-resistance is the property: a tool the consumer may not call and one that does
        // not exist answer the same. The code and message still match the SDK's own; the data
        // field no longer does, because the SDK names the tool the caller asked for and we do
        // not return the caller's text.
        CapturingTransport bare = new CapturingTransport();
        McpStatelessSyncServer sdk = McpServer.sync(bare)
                .serverInfo("bare", "0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .immediateExecution(true)
                .build();
        try {
            McpSchema.JSONRPCRequest probe = request(McpSchema.METHOD_TOOLS_CALL, Map.of("name", "mcp_echo", "arguments", Map.of()));
            McpSchema.JSONRPCResponse genuine = bare.handler.handleRequest(McpTransportContext.EMPTY, probe).block();
            McpSchema.JSONRPCResponse ours = transport.handler.handleRequest(as(BOB), probe).block();
            assertNotNull(genuine.error());
            assertNotNull(ours.error());
            assertEquals(genuine.error().code(), ours.error().code());
            assertEquals(genuine.error().message(), ours.error().message());
            assertEquals(genuine.result(), ours.result());
            assertEquals(genuine.id(), ours.id());
            assertFalse(String.valueOf(genuine.error().data()).equals(String.valueOf(ours.error().data())),
                    "the SDK names the tool asked for; we do not");
            McpSchema.JSONRPCResponse nonexistent = send(as(BOB), McpSchema.METHOD_TOOLS_CALL, Map.of("name", "no_such_tool"));
            assertEquals(ours.error().code(), nonexistent.error().code());
            assertEquals(ours.error().message(), nonexistent.error().message());
            assertEquals(ours.error().data(), nonexistent.error().data(), "ungranted and nonexistent are one answer");
        }
        finally {
            sdk.close();
        }
    }

    @Test
    void unauthenticatedAndMalformedCallsAreRefusedAsUnknownTool() {
        McpSchema.JSONRPCResponse unauthenticated = send(McpTransportContext.EMPTY, McpSchema.METHOD_TOOLS_CALL, Map.of("name", "mcp_ping"));
        assertEquals(McpSchema.ErrorCodes.INVALID_PARAMS, unauthenticated.error().code());
        McpSchema.JSONRPCResponse blank = send(as(" "), McpSchema.METHOD_TOOLS_CALL, Map.of("name", "mcp_ping"));
        assertEquals(McpSchema.ErrorCodes.INVALID_PARAMS, blank.error().code());
        McpSchema.JSONRPCResponse malformed = send(as(ALICE), McpSchema.METHOD_TOOLS_CALL, "garbage");
        assertEquals(McpSchema.ErrorCodes.INVALID_PARAMS, malformed.error().code());
        assertEquals("Tool not found", malformed.error().data(),
                "constant: the name a caller sent is the caller's text and does not come back");
    }

    @Test
    void toolNameIsReadFromMapOrJsonNode() {
        assertEquals("x", McpTransportInterposer.toolNameOf(Map.of("name", "x")));
        assertEquals("y", McpTransportInterposer.toolNameOf(NucleoJsonSerializer.valueToTree(Map.of("name", "y"))));
        assertNull(McpTransportInterposer.toolNameOf(Map.of("name", 7)));
        assertNull(McpTransportInterposer.toolNameOf(List.of("name")));
        assertNull(McpTransportInterposer.toolNameOf(null));
    }

    // ---- calls ----

    @Test
    void grantedCallRunsUnderTheConsumerPrincipal() {
        // artifact_refs is not published and is therefore refused if a consumer sends it;
        // McpHostileInputTest pins that. A granted call sends what the schema declares.
        McpSchema.CallToolResult result = call(as(ALICE), "mcp_echo", Map.of("text", "hello"));
        assertNotEquals(Boolean.TRUE, result.isError(), () -> text(result));
        String text = text(result);
        assertTrue(text.contains("\"hello\""), text);
        assertTrue(text.contains("\"" + ALICE + "\""), "the served tool ran as the consumer: " + text);
    }

    @Test
    void nullArgumentsAreAnEmptyInput() {
        McpSchema.CallToolResult result = call(as(BOB), "mcp_ping", null);
        assertNotEquals(Boolean.TRUE, result.isError(), () -> text(result));
        assertEquals("\"pong\"", text(result));
    }

    @Test
    void correctableFailureIsAnErrorResultFlaggedCorrectable() {
        McpSchema.CallToolResult result = call(as(ALICE), "mcp_faulty", Map.of("mode", FaultInput.CORRECTABLE));
        assertEquals(Boolean.TRUE, result.isError());
        assertEquals(Boolean.TRUE, result.meta().get("correctable"));
        assertTrue(text(result).contains("refuse this input"), text(result));
    }

    @Test
    void schemaViolationIsRejectedAtTheDoorByOurOwnGate() {
        // McpInputGate judges ahead of the SDK's own validation, so the refusal a consumer
        // reads is one this process composed: it names the declared type, carries the
        // correctable marker, and quotes nothing.
        McpSchema.CallToolResult result = call(as(ALICE), "mcp_faulty", Map.of("mode", Map.of("not", "a string")));
        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(text(result).contains("must be a string"), text(result));
        assertEquals(Boolean.TRUE, result.meta().get("correctable"), "a caller can fix this and retry");
    }

    @Test
    void runtimeFailureNeverReachesTheSdkMapping() {
        McpSchema.CallToolResult result = call(as(ALICE), "mcp_faulty", Map.of("mode", FaultInput.RUNTIME));
        assertEquals(Boolean.TRUE, result.isError());
        assertEquals(Boolean.FALSE, result.meta().get("correctable"));
        assertFalse(text(result).contains("boom"), "internals never reach the caller, the log carries the cause: " + text(result));
        assertEquals(new SystemException("McpToolServer", "boom", null).getLLMMessage(), text(result));
    }

    @Test
    void aRecursiveOutputSchemaIsOneTheSdkCanValidate() {
        // A self-referential output publishes a $ref into a $defs section. The SDK validates
        // every structured result against the schema we published, so a reference it could
        // not resolve would fail every call to the tool - and only over a real transport.
        McpSchema.CallToolResult result = call(as(ALICE), "mcp_tree", Map.of());
        assertNotEquals(Boolean.TRUE, result.isError(), () -> text(result));
        assertNotNull(result.structuredContent(), "validated against a schema carrying $defs");
        assertTrue(text(result).contains("leaf"), text(result));
    }

    @Test
    void structuredContentRidesAnObjectOutput() {
        McpSchema.CallToolResult result = call(as(ALICE), "mcp_echo", Map.of("text", "hello"));
        assertNotEquals(Boolean.TRUE, result.isError(), () -> text(result));
        assertNotNull(result.structuredContent(),
                "a declared output schema obliges every result to carry conforming structured content");
    }

    @Test
    void aScalarOutputSendsNoStructuredContent() {
        McpSchema.CallToolResult result = call(as(BOB), "mcp_ping", Map.of());
        assertNotEquals(Boolean.TRUE, result.isError(), () -> text(result));
        assertNull(result.structuredContent(),
                "no output schema means no structured content - the SDK only warns about the mismatch, so nothing else would catch it");
    }

    @Test
    void anArtifactResultCrossesWithItsIdentity() {
        McpSchema.CallToolResult result = call(as(ALICE), "mcp_citing", Map.of());
        assertNotEquals(Boolean.TRUE, result.isError(), () -> text(result));

        TypeAliasRegistry.register(ServedCitation.class);
        Artifact received = MCPArtifactRehydrator.rehydrate(text(result));
        assertInstanceOf(ServedCitation.class, received,
                "a served artifact rebuilds into its own type on a redouble consumer: " + text(result));
        assertEquals(CitingTool.DOI, ((ServedCitation)received).getDoi(), "the identifier crosses intact");
        assertNotNull(received.getArtifactRef(),
                "the serving layer mints the ref; without it the payload arrives as anonymous data");
    }

    @Test
    void nullResultSerializesAsJsonNull() {
        McpSchema.CallToolResult result = call(as(ALICE), "mcp_faulty", Map.of("mode", FaultInput.NULL));
        assertNotEquals(Boolean.TRUE, result.isError(), () -> text(result));
        assertEquals("null", text(result));
    }

    @Test
    void timeoutCancelsTheWholeWorkflow() throws Exception {
        long started = System.currentTimeMillis();
        McpSchema.CallToolResult result = call(as(ALICE), "mcp_slow_parent", Map.of());
        assertEquals(Boolean.TRUE, result.isError());
        assertEquals(new JobTimeoutException(CALL_TIMEOUT, null).getLLMMessage(), text(result));
        assertTrue(System.currentTimeMillis() - started < FaultyTool.SLOW_RUN.toMillis(), "the caller is released at the timeout");
        SlowChildTool child = SlowParentDoer.LAST_CHILD.get();
        assertNotNull(child);
        long deadline = System.currentTimeMillis() + 5_000;
        while (!child.observedCancellation.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(child.observedCancellation.get(), "the spawned child observed the workflow cancellation");
    }

}
