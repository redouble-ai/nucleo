/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.server;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.http.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.mcp.server.fixtures.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.registry.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.modelcontextprotocol.common.*;
import io.modelcontextprotocol.server.*;
import io.modelcontextprotocol.spec.*;
import org.junit.jupiter.api.*;
import reactor.core.publisher.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every dialect of the MCP server, under the same two rules as the canonical one: anything
 * unexpected is refused, and nothing a caller sent comes back. A dialect is a spelling of
 * one contract, so every payload the canonical campaign refuses is refused in every
 * dialect, with the same correctable marker and without an echo, and every payload it
 * carries is carried in every dialect. The gate judges the canonical form whichever dialect
 * a request names; a dialect's inverse turns the caller's spelling into that form first.
 *
 * <p>One dialect changes the documents a caller sends. OPENAI_STRICT spells every property
 * as required and an optional one as nullable, so a caller reading that spelling sends every
 * property, the omitted optionals as null. Its inverse removes a null only where the
 * canonical schema declares an optional property; a null on a required property is a
 * missing property, and a null under a name the schema does not declare is an undeclared
 * property, and both are refused as such. That is the rule the corpus here pins on top of
 * the canonical one.
 *
 * <p>What is not here: the decoder. This harness hands the interposer JSON-RPC objects, so
 * no bytes reach a transport. The boundary reader a host wires to the one endpoint is
 * {@code McpBoundaryJson}, and its raw corpus is {@code McpBoundaryJsonTest}; a host that
 * wires another reader has left the contract, and the host's own campaign is where that
 * is pinned.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-06)
 */
public class McpDialectHostileInputTest {
    static final String CONSUMER = "consumer";
    static final String STRANGER = "stranger";
    static final String PRINCIPAL = "principal";
    static final String CANARY = McpHostileInputTest.CANARY;
    /** For a hostile KEY: the name a caller invented must not come back any more than a value. */
    static final String KEY_CANARY = "Xk9KeyCanary";
    /** Every optional property of StrictInput: what a strict caller sends as null when it has nothing for it. */
    static final List<String> OPTIONALS = List.of("count", "loud", "tags", "nested", "when", "mode", "at", "ratio");
    static final String NULL_ON_OPTIONAL = "null on an optional parameter";
    static CapturingTransport transport;
    static McpToolServer server;
    static final AtomicInteger ids = new AtomicInteger();
    static final Duration CALL_TIMEOUT = Duration.ofSeconds(2);
    /** The whole JSON-RPC response of the last call, the wire as a consumer sees it. */
    static volatile String lastWire;

    static class CapturingTransport implements McpStatelessServerTransport {
        volatile McpStatelessServerHandler handler;

        @Override
        public void setMcpHandler(McpStatelessServerHandler handler) {
            this.handler = handler;
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.empty();
        }
    }

    @BeforeAll
    static void startServer() {
        JobDispatcher.getInstance().start();
        transport = new CapturingTransport();
        server = new McpToolServer();
        server.setScanPackages(List.of(McpToolCatalogTest.FIXTURES));
        server.setProviders(List.of());
        server.setConsumerResolver(context -> context.get(PRINCIPAL) instanceof String name ? new McpConsumer(name, Set.of()) : null);
        server.setAccessPolicy((consumer, provider) -> CONSUMER.equals(consumer.name()));
        server.setServerName("dialect-test");
        server.setServerVersion("0");
        server.setCallTimeout(CALL_TIMEOUT);
        server.setTransport(transport);
        server.start();
        assertNotNull(transport.handler, "the SDK server installs its handler through the interposer");
    }

    @AfterAll
    static void closeServer() {
        server.close();
    }

    // ---- request helpers ----

    static McpTransportContext as(String consumer) {
        return McpTransportContext.create(Map.of(PRINCIPAL, consumer));
    }

    /**
     * Sends one request naming the dialect the way a caller does: the optional {@code _meta}
     * key on the request's params. The canonical dialect is named explicitly half the time
     * and omitted the other half, since both spell the same thing and both must work.
     */
    static McpSchema.JSONRPCResponse send(SchemaDialect dialect, McpTransportContext context, String method, Object params) {
        Object named = params;
        if (dialect != SchemaDialect.CANONICAL || ids.get() % 2 == 0) {
            Map<String, Object> withMeta = new LinkedHashMap<>();
            if (params instanceof Map<?, ?> map) {
                map.forEach((key, value) -> withMeta.put(String.valueOf(key), value));
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            Object existing = withMeta.get("_meta");
            if (existing instanceof Map<?, ?> map) {
                map.forEach((key, value) -> meta.put(String.valueOf(key), value));
            }
            meta.put(SchemaDialect.META_KEY, dialect.wireName());
            withMeta.put("_meta", meta);
            named = withMeta;
        }
        return transport.handler.handleRequest(context,
                new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, method, ids.incrementAndGet(), named)).block();
    }

    static McpSchema.CallToolResult call(SchemaDialect dialect, String consumer, String tool, Object arguments) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", tool);
        params.put("arguments", arguments);
        McpSchema.JSONRPCResponse response = send(dialect, as(consumer), McpSchema.METHOD_TOOLS_CALL, params);
        lastWire = NucleoJsonSerializer.write(response);
        assertNull(response.error(), () -> dialect + ": answered a protocol error instead of a result: " + response.error());
        return (McpSchema.CallToolResult)response.result();
    }

    static McpSchema.CallToolResult callStrictTool(SchemaDialect dialect, Map<String, Object> arguments) {
        return call(dialect, CONSUMER, "mcp_strict", arguments);
    }

    static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent)result.content().get(0)).text();
    }

    static McpSchema.Tool listed(SchemaDialect dialect, String name) {
        McpSchema.JSONRPCResponse response = send(dialect, as(CONSUMER), McpSchema.METHOD_TOOLS_LIST, Map.of());
        assertNull(response.error());
        McpSchema.ListToolsResult listing = (McpSchema.ListToolsResult)response.result();
        return listing.tools().stream().filter(tool -> tool.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(name + " is not listed on " + dialect));
    }

    /**
     * A payload as a caller reading the OpenAI strict spelling sends it: every property present, the
     * optional ones the caller has nothing for as null. The canonical corpus omits them.
     */
    static Map<String, Object> inTheSpellingOf(SchemaDialect dialect, Map<String, Object> arguments) {
        if (dialect != SchemaDialect.OPENAI_STRICT) {
            return arguments;
        }
        Map<String, Object> spelled = new LinkedHashMap<>(arguments);
        for (String optional : OPTIONALS) {
            if (!spelled.containsKey(optional)) {
                spelled.put(optional, null);
            }
        }
        return spelled;
    }

    /** A stand-in for "this dialect spells an omission as an explicit null", since a map cannot hold one as a flag. */
    static Object nullValue() {
        return "";
    }

    static Stream<SchemaDialect> dialects() {
        return Arrays.stream(SchemaDialect.values());
    }

    // ---- rule one, in every dialect: an unexpected shape is refused ----

    @TestFactory
    Stream<DynamicTest> everyMalformedPayloadIsRefusedOnEveryDialect() {
        return dialects().flatMap(dialect -> McpHostileInputTest.malformed().stream()
                .filter(hostile -> dialect != SchemaDialect.OPENAI_STRICT || !NULL_ON_OPTIONAL.equals(hostile.name()))
                .map(hostile -> DynamicTest.dynamicTest(dialect + ": " + hostile.name(), () -> {
                    McpSchema.CallToolResult result = callStrictTool(dialect, inTheSpellingOf(dialect, hostile.arguments()));
                    assertEquals(Boolean.TRUE, result.isError(),
                            () -> dialect + " accepted what it should have refused - " + text(result));
                    assertEquals(Boolean.TRUE, result.meta().get(McpToolServer.META_CORRECTABLE),
                            () -> dialect + ": a caller that can fix its input must be told so, by this process - " + text(result));
                })));
    }

    @Test
    void aNullOnAnOptionalIsTheStrictSpellingOfOmissionAndNowhereElse() {
        // The one payload whose meaning depends on the dialect. In the strict spelling it is
        // how a caller omits; in every other it is a value of the wrong type.
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("text", "fine " + CANARY);
        arguments.put("count", null);
        for (SchemaDialect dialect : SchemaDialect.values()) {
            McpSchema.CallToolResult result = callStrictTool(dialect, inTheSpellingOf(dialect, arguments));
            if (dialect == SchemaDialect.OPENAI_STRICT) {
                assertNotEquals(Boolean.TRUE, result.isError(), () -> "the strict spelling of an omitted optional: " + text(result));
            }
            else {
                assertEquals(Boolean.TRUE, result.isError(), () -> dialect + " accepted a null where an integer is declared");
                assertTrue(text(result).contains("count"), text(result));
            }
        }
    }

    // ---- rule one, the other half: a correct shape is accepted whatever it says ----

    @TestFactory
    Stream<DynamicTest> hostileContentIsCarriedOnEveryDialect() {
        return dialects().flatMap(dialect -> McpHostileInputTest.hostileContent().stream()
                .map(hostile -> DynamicTest.dynamicTest(dialect + ": " + hostile.name(), () -> {
                    McpSchema.CallToolResult result = callStrictTool(dialect, inTheSpellingOf(dialect, hostile.arguments()));
                    assertNotEquals(Boolean.TRUE, result.isError(),
                            () -> dialect + " refused content, which is the guardrail layer's job - " + text(result));
                })));
    }

    @TestFactory
    Stream<DynamicTest> everyValueSurvivesTheCrossingOnEveryDialect() {
        // Accepting a call proves nothing about what the tool got. The fixture reports the
        // input object it was handed, so this pins the other direction: a dialect whose
        // inverse dropped a field, or whose spelling turned one into something else, fails
        // here rather than passing as a call that merely did not error.
        return dialects().map(dialect -> DynamicTest.dynamicTest(dialect.name(), () -> {
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("text", "hello");
            arguments.put("count", 3);
            arguments.put("loud", true);
            arguments.put("tags", List.of("a", "b"));
            arguments.put("nested", Map.of("note", "n"));
            arguments.put("when", "2026-09-06");
            arguments.put("mode", "FAST");
            arguments.put("at", "2026-09-06T10:15:00");
            arguments.put("ratio", 0.25);
            McpSchema.CallToolResult result = callStrictTool(dialect, arguments);
            assertNotEquals(Boolean.TRUE, result.isError(), () -> dialect + ": " + text(result));
            assertTrue(text(result).contains("accepted 8 optional field(s): text=5 count=3 loud=true tags=2 nested=present"
                            + " when=2026-09-06 mode=FAST at=2026-09-06T10:15 ratio=0.25"),
                    dialect + " delivered something other than what was sent: " + text(result));
        }));
    }

    @TestFactory
    Stream<DynamicTest> anOmittedOptionalArrivesAsAbsentOnEveryDialect() {
        // The other half of the crossing, and the one the strict spelling can break: there a
        // caller sends every optional as null, and the inverse has to make that an omission
        // rather than a null the tool then stores.
        return dialects().map(dialect -> DynamicTest.dynamicTest(dialect.name(), () -> {
            McpSchema.CallToolResult result = callStrictTool(dialect, inTheSpellingOf(dialect, new LinkedHashMap<>(Map.of("text", "hello"))));
            assertNotEquals(Boolean.TRUE, result.isError(), () -> dialect + ": " + text(result));
            assertTrue(text(result).contains("accepted 0 optional field(s): text=5 count=null loud=null tags=null nested=null"
                            + " when=null mode=null at=null ratio=null"),
                    dialect + " turned an omission into something else: " + text(result));
        }));
    }

    @TestFactory
    Stream<DynamicTest> aRecursiveInputCrossesEveryDialectAndIsJudgedAtEveryDepth() {
        // Four dialects cannot spell recursion and publish a shapeless object where the tree
        // continues, so their first pass accepts anything below the first level. The canonical
        // pass is what still refuses, and this is the only served tool whose input can show it.
        return dialects().map(dialect -> DynamicTest.dynamicTest(dialect.name(), () -> {
            // The strict spelling requires every property at every depth, so a caller reading
            // it spells a leaf's absent children as null all the way down.
            Object leafChildren = dialect == SchemaDialect.OPENAI_STRICT ? nullValue() : null;
            Map<String, Object> leaf = new LinkedHashMap<>();
            leaf.put("label", "aa");
            if (leafChildren != null) {
                leaf.put("children", null);
            }
            Map<String, Object> deep = Map.of("label", "root", "children",
                    List.of(Map.of("label", "a", "children", List.of(leaf))));
            McpSchema.CallToolResult accepted = call(dialect, CONSUMER, "mcp_plant", deep);
            assertNotEquals(Boolean.TRUE, accepted.isError(), () -> dialect + ": " + text(accepted));
            assertTrue(text(accepted).contains("3"), dialect + " lost a node on the way in: " + text(accepted));
            Map<String, Object> hostileLeaf = new LinkedHashMap<>(leaf);
            hostileLeaf.put("admin" + KEY_CANARY, CANARY);
            Map<String, Object> undeclaredDeep = Map.of("label", "root", "children",
                    List.of(Map.of("label", "a", "children", List.of(hostileLeaf))));
            McpSchema.CallToolResult refused = call(dialect, CONSUMER, "mcp_plant", undeclaredDeep);
            assertEquals(Boolean.TRUE, refused.isError(), () -> dialect + " admitted an undeclared property three levels down");
            assertEquals(Boolean.TRUE, refused.meta().get(McpToolServer.META_CORRECTABLE), dialect.name());
            assertFalse(lastWire.contains(CANARY) || lastWire.contains(KEY_CANARY), lastWire);
        }));
    }

    // ---- rule two, in every dialect: nothing sent comes back ----

    @TestFactory
    Stream<DynamicTest> noResponseQuotesWhatWasSentOnAnyDialect() {
        // Every response, refusal or result, judged whole as the JSON-RPC envelope the
        // consumer sees; the strict tool's result says how many optionals it got, never what
        // they said, so a canary anywhere in a response is an echo.
        return dialects().flatMap(dialect -> McpHostileInputTest.corpus().stream()
                .map(hostile -> DynamicTest.dynamicTest(dialect + ": " + hostile.name(), () -> {
                    McpSchema.CallToolResult result = callStrictTool(dialect, inTheSpellingOf(dialect, hostile.arguments()));
                    String wire = lastWire;
                    assertFalse(wire.contains(CANARY), () -> dialect + ": the response reflected the caller's own text - " + wire);
                    assertFalse(wire.contains(McpHostileInputTest.KEY_CANARY), () -> dialect + ": the response reflected a name the caller invented - " + wire);
                    if (Boolean.TRUE.equals(result.isError())) {
                        assertFalse(text(result).isBlank(), () -> dialect + ": a refusal still has to mean something");
                    }
                })));
    }

    @TestFactory
    Stream<DynamicTest> aDateTheParserRejectsIsRefusedWithoutQuotingItOnEveryDialect() {
        return dialects().flatMap(dialect -> McpHostileInputTest.unparsableDates().stream()
                .map(bad -> DynamicTest.dynamicTest(dialect + ": " + bad, () -> {
                    Map<String, Object> arguments = new LinkedHashMap<>(Map.of("text", "fine", "when", bad));
                    McpSchema.CallToolResult result = callStrictTool(dialect, inTheSpellingOf(dialect, arguments));
                    assertEquals(Boolean.TRUE, result.isError(), () -> dialect + " accepted an unparsable date: " + text(result));
                    assertFalse(lastWire.contains(CANARY), "the parser's complaint reached the caller: " + lastWire);
                    assertFalse(lastWire.contains(bad), "the value came back: " + lastWire);
                    assertTrue(text(result).contains("when"), "the parameter is ours to name: " + text(result));
                })));
    }

    @TestFactory
    Stream<DynamicTest> aNumberTheFieldCannotHoldIsRefusedOnEveryDialect() {
        return dialects().flatMap(dialect -> McpHostileInputTest.magnitudes().stream()
                .map(raw -> DynamicTest.dynamicTest(dialect + ": " + (raw.length() > 24 ? raw.length() + " digits" : raw), () -> {
                    ObjectNode arguments = NucleoJsonSerializer.createObjectNode();
                    arguments.put("text", "fine");
                    arguments.set("count", NucleoJsonSerializer.readTree(raw));
                    if (dialect == SchemaDialect.OPENAI_STRICT) {
                        for (String optional : OPTIONALS) {
                            if (!arguments.has(optional)) {
                                arguments.putNull(optional);
                            }
                        }
                    }
                    McpSchema.CallToolResult result = call(dialect, CONSUMER, "mcp_strict", arguments);
                    assertEquals(Boolean.TRUE, result.isError(), () -> dialect + " accepted " + raw + ": " + text(result));
                    assertTrue(text(result).contains("count"), text(result));
                })));
    }

    @TestFactory
    Stream<DynamicTest> anIntegerAtTheBoundsIsAcceptedOnEveryDialect() {
        return dialects().map(dialect -> DynamicTest.dynamicTest(dialect.name(), () -> {
            for (int bound : new int[] {Integer.MAX_VALUE, Integer.MIN_VALUE}) {
                Map<String, Object> arguments = new LinkedHashMap<>(Map.of("text", "fine", "count", bound));
                McpSchema.CallToolResult result = callStrictTool(dialect, inTheSpellingOf(dialect, arguments));
                assertNotEquals(Boolean.TRUE, result.isError(), () -> dialect + " refused " + bound + ": " + text(result));
            }
        }));
    }

    @TestFactory
    Stream<DynamicTest> theUnpublishedBoundaryFieldIsRefusedOnTheToolThatDeclaresIt() {
        // The field exists on ThinkerInput and is removed from every published schema, so a
        // caller that sends it is sending an undeclared property. Judged against a served
        // tool whose input really declares it: on any other tool the refusal would prove
        // only that the name is unknown to that tool.
        return dialects().map(dialect -> DynamicTest.dynamicTest(dialect.name(), () -> {
            McpSchema.Tool published = listed(dialect, "mcp_refs");
            String schema = NucleoJsonSerializer.write(published.inputSchema());
            assertFalse(schema.contains(McpSchemaPublisher.ARTIFACT_REFS), dialect + " publishes the boundary field: " + schema);
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("query", "a legitimate query");
            arguments.put(McpSchemaPublisher.ARTIFACT_REFS, List.of("«artifact:link~" + CANARY + "»"));
            McpSchema.CallToolResult result = call(dialect, CONSUMER, "mcp_refs", arguments);
            assertEquals(Boolean.TRUE, result.isError(), () -> dialect + " accepted a reference into this process's registry: " + text(result));
            assertEquals(Boolean.TRUE, result.meta().get(McpToolServer.META_CORRECTABLE));
            assertTrue(text(result).contains("not declared"), text(result));
            assertFalse(lastWire.contains(CANARY), lastWire);
        }));
    }

    @Test
    void aToolNameThatIsNotAStringIsParamsInAnUnreadableShape() {
        // The other half of the unreadable-params rule, beside a _meta that is not an object.
        int id = ids.incrementAndGet();
        McpSchema.JSONRPCResponse refused = transport.handler.handleRequest(as(CONSUMER),
                new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, McpSchema.METHOD_TOOLS_CALL, id,
                        Map.of("name", 7, "arguments", Map.of("text", "fine " + CANARY)))).block();
        McpSchema.JSONRPCResponse unknown = transport.handler.handleRequest(as(CONSUMER),
                new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, McpSchema.METHOD_TOOLS_CALL, id,
                        Map.of("name", "mcp_" + KEY_CANARY, "arguments", Map.of()))).block();
        String wire = NucleoJsonSerializer.write(refused);
        assertEquals(NucleoJsonSerializer.write(unknown), wire, "the one constant answer for an unreadable call");
        assertFalse(wire.contains(CANARY), wire);
    }

    @Test
    void anUnknownDialectIsRefusedWithTheAcceptedOnesNamed() {
        // The dialect is an optional parameter, so a value that names no dialect is a
        // caller's fixable mistake: refused with the accepted names, which are ours, and
        // never the invented one, which is the caller's text.
        for (String method : List.of(McpSchema.METHOD_TOOLS_CALL, McpSchema.METHOD_TOOLS_LIST)) {
            Map<String, Object> params = new LinkedHashMap<>();
            if (McpSchema.METHOD_TOOLS_CALL.equals(method)) {
                params.put("name", "mcp_strict");
                params.put("arguments", Map.of("text", "fine"));
            }
            params.put("_meta", Map.of(SchemaDialect.META_KEY, "klingon_" + KEY_CANARY));
            McpSchema.JSONRPCResponse response = transport.handler.handleRequest(as(CONSUMER),
                    new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, method, ids.incrementAndGet(), params)).block();
            assertNotNull(response.error(), method);
            assertEquals(McpSchema.ErrorCodes.INVALID_PARAMS, response.error().code(), method);
            String wire = NucleoJsonSerializer.write(response);
            assertFalse(wire.contains(KEY_CANARY), "the invented name came back: " + wire);
            for (SchemaDialect dialect : SchemaDialect.values()) {
                assertTrue(response.error().message().contains(dialect.wireName()), "the accepted names are ours to name: " + wire);
            }
        }
    }

    @TestFactory
    Stream<DynamicTest> anUndeclaredPropertyDeepInsideANestedDocumentIsRefused() {
        // The gate's rule is "at any depth", so the corpus reaches past one level.
        return dialects().map(dialect -> DynamicTest.dynamicTest(dialect.name(), () -> {
            Map<String, Object> arguments = new LinkedHashMap<>(Map.of("text", "fine " + CANARY));
            arguments.put("nested", Map.of("note", "n", "admin" + KEY_CANARY, Map.of("deeper", CANARY)));
            McpSchema.CallToolResult result = callStrictTool(dialect, inTheSpellingOf(dialect, arguments));
            assertEquals(Boolean.TRUE, result.isError(), () -> dialect + ": " + text(result));
            assertFalse(lastWire.contains(CANARY) || lastWire.contains(KEY_CANARY), lastWire);
        }));
    }

    @TestFactory
    Stream<DynamicTest> argumentsThatAreNotAnObjectAreRefusedOnEveryDialect() {
        // The gate's first question, asked through a real request rather than of the gate alone:
        // a scalar, an array and a null where an object is the only shape a call may carry.
        return dialects().flatMap(dialect -> Stream.of("a string", List.of(CANARY), (Object)null)
                .map(arguments -> DynamicTest.dynamicTest(dialect + ": " + (arguments == null ? "null" : arguments.getClass().getSimpleName()), () -> {
                    Map<String, Object> params = new LinkedHashMap<>();
                    params.put("name", "mcp_strict");
                    params.put("arguments", arguments == null ? null : arguments instanceof String ? CANARY : arguments);
                    McpSchema.JSONRPCResponse response = send(dialect, as(CONSUMER), McpSchema.METHOD_TOOLS_CALL, params);
                    String wire = NucleoJsonSerializer.write(response);
                    assertFalse(wire.contains(CANARY), () -> dialect + ": the response reflected the caller's own text - " + wire);
                    if (response.error() == null) {
                        assertEquals(Boolean.TRUE, ((McpSchema.CallToolResult)response.result()).isError(),
                                () -> dialect + " accepted arguments that are not an object: " + wire);
                    }
                })));
    }

    @TestFactory
    Stream<DynamicTest> aDocumentNestedPastAnythingReasonableIsRefusedWithoutAnEcho() {
        // Deep, but inside the decoder's bound, so the gate is what answers: every level
        // below the first carries a property the schema does not declare.
        return dialects().map(dialect -> DynamicTest.dynamicTest(dialect.name(), () -> {
            Map<String, Object> arguments = new LinkedHashMap<>(Map.of("text", "fine " + CANARY));
            arguments.put("nested", McpHostileInputTest.nest(50));
            McpSchema.CallToolResult result = callStrictTool(dialect, inTheSpellingOf(dialect, arguments));
            assertEquals(Boolean.TRUE, result.isError(), () -> dialect + ": " + text(result));
            assertFalse(lastWire.contains(CANARY), lastWire);
        }));
    }

    @Test
    void noRefusalCarriesTheCallersTextAnywhereInItsCauseChain() {
        // The wire is what a consumer sees; the chain is what a log, a job record or a
        // rethrow would carry. Judged where the harness can hold the exception itself.
        ToolProvider provider = ClassToolProvider.of(ai.redouble.nucleo.mcp.server.fixtures.StrictTool.class);
        List<Map<String, Object>> payloads = new ArrayList<>();
        for (McpHostileInputTest.Hostile hostile : McpHostileInputTest.malformed()) {
            payloads.add(hostile.arguments());
        }
        for (Map<String, Object> arguments : payloads) {
            try {
                McpInputGate.admit(provider, McpBoundaryJson.TREE_MAPPER.valueToTree(arguments));
            }
            catch (Exception refused) {
                for (Throwable link = refused; link != null; link = link.getCause()) {
                    String said = link.getMessage() + " " + link
                                  + (link instanceof LLMReadable readable ? " " + readable.getLLMMessage() : "");
                    assertFalse(said.contains(CANARY), () -> "a link of the chain quoted the caller's value: " + said);
                    assertFalse(said.contains(McpHostileInputTest.KEY_CANARY), () -> "a link of the chain quoted a name the caller invented: " + said);
                }
            }
        }
    }

    @Test
    void aListingOfAShapeTheSdkNeverProducesListsNothing() {
        // The interposer's fail-closed rule, driven by handing it a delegate that answers
        // tools/list with something that is not a listing.
        CapturingTransport inner = new CapturingTransport();
        McpTransportInterposer interposer = new McpTransportInterposer(inner, server.getCatalog(),
                context -> new McpConsumer(CONSUMER, Set.of()), (consumer, provider) -> true);
        interposer.setMcpHandler(new McpStatelessServerHandler() {
            @Override
            public Mono<McpSchema.JSONRPCResponse> handleRequest(McpTransportContext context, McpSchema.JSONRPCRequest request) {
                return Mono.just(new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), "not a listing " + CANARY, null));
            }

            @Override
            public Mono<Void> handleNotification(McpTransportContext context, McpSchema.JSONRPCNotification notification) {
                return Mono.empty();
            }
        });
        McpSchema.JSONRPCResponse response = inner.handler.handleRequest(as(CONSUMER),
                new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, McpSchema.METHOD_TOOLS_LIST, ids.incrementAndGet(), Map.of())).block();
        assertNull(response.error());
        assertTrue(((McpSchema.ListToolsResult)response.result()).tools().isEmpty(), "a result of an unknown shape lists nothing");
        assertFalse(NucleoJsonSerializer.write(response).contains(CANARY), "and says nothing of what it saw");
    }

    @TestFactory
    Stream<DynamicTest> aDelegatedMethodInAnUnreadableShapeDoesNotEchoTheParsersComplaint() {
        // tools/list and ping are delegated with their params unexamined, the same seam as
        // initialize. Unlike initialize, the SDK reads nothing of their params it can fail
        // on, so a wrong shape is ignored rather than quoted; pinned so that a future SDK
        // that starts reading them cannot open the seam quietly.
        return Stream.of(McpSchema.METHOD_TOOLS_LIST, McpSchema.METHOD_PING)
                .map(method -> DynamicTest.dynamicTest(method, () -> {
                    McpSchema.JSONRPCResponse response = send(SchemaDialect.CANONICAL, as(CONSUMER), method,
                            Map.of("cursor", Map.of("probe", CANARY)));
                    String wire = NucleoJsonSerializer.write(response);
                    assertFalse(wire.contains(CANARY), method + ": the parser's complaint reached the caller - " + wire);
                }));
    }

    @TestFactory
    Stream<DynamicTest> aRuleADialectCannotSpellIsStillEnforcedAndStillStated() {
        // The two-pass judgement, from the caller's side. Gemini has no keyword for an integer
        // bound, so its published schema permits a value the tool cannot hold; the second pass
        // against the canonical schema refuses it, in our words and with the correctable
        // marker, and the published description told the caller the bound in the first place.
        return dialects().map(dialect -> DynamicTest.dynamicTest(dialect.name(), () -> {
            ObjectNode arguments = NucleoJsonSerializer.createObjectNode();
            arguments.put("text", "fine");
            arguments.put("count", 2147483648L);
            if (dialect == SchemaDialect.OPENAI_STRICT) {
                for (String optional : OPTIONALS) {
                    if (!arguments.has(optional)) {
                        arguments.putNull(optional);
                    }
                }
            }
            McpSchema.CallToolResult result = call(dialect, CONSUMER, "mcp_strict", arguments);
            assertEquals(Boolean.TRUE, result.isError(), () -> dialect + " accepted a value the field cannot hold: " + text(result));
            assertEquals(Boolean.TRUE, result.meta().get(McpToolServer.META_CORRECTABLE), dialect.name());
            assertTrue(text(result).contains("count"), text(result));
            McpSchema.Tool published = listed(dialect, "mcp_strict");
            String describes = NucleoJsonSerializer.write(published.inputSchema());
            assertTrue(describes.contains("2147483647"),
                    dialect + " enforces a bound it never told the caller about: " + describes);
        }));
    }

    @Test
    void aPropertyThePublishedSchemaLeavesUntypedIsCarried() {
        // The gate judges what the document declares. A property published without a type
        // accepts any JSON: refusing it would refuse what the contract allowed, and typing it
        // in the gate would be a rule no consumer was told.
        ToolProvider provider = ClassToolProvider.of(StrictTool.class);
        ObjectNode schema = (ObjectNode)McpSchemaPublisher.canonicalInputSchema(provider);
        ObjectNode open = NucleoJsonSerializer.createObjectNode();
        open.put("description", "anything at all");
        ((ObjectNode)schema.get("properties")).set("open", open);
        ObjectNode arguments = NucleoJsonSerializer.createObjectNode();
        arguments.put("text", "fine");
        arguments.set("open", NucleoJsonSerializer.createArrayNode().add(1).add("two"));
        assertDoesNotThrow(() -> McpInputGate.admit(new FixedSchemaProvider(provider, schema), arguments));
    }

    /** A provider that publishes a schema the test composed, to reach a shape no generator emits. */
    record FixedSchemaProvider(ToolProvider delegate, JsonNode schema) implements ToolProvider {
        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public String description() {
            return delegate.description();
        }

        @Override
        public String schemaJson() {
            return NucleoJsonSerializer.writeCompact(schema);
        }

        @Override
        public ToolWeight weight() {
            return delegate.weight();
        }

        @Override
        public String displayName() {
            return delegate.displayName();
        }

        @Override
        public String actionVerb() {
            return delegate.actionVerb();
        }

        @Override
        public Class<?> inputType() {
            return delegate.inputType();
        }

        @Override
        public Class<? extends Tool> toolClass() {
            return delegate.toolClass();
        }

        @Override
        public boolean readOnly() {
            return delegate.readOnly();
        }

        @Override
        public Object parseInput(JsonNode arguments) throws CorrectableLLMException {
            return delegate.parseInput(arguments);
        }

        @Override
        public Tool<?, ?> create(Identifiable parent) throws LLMReadableCheckedException {
            return delegate.create(parent);
        }
    }

    @Test
    void theBranchesTheRendererComposesForwardNothingTheyWereHanded() {
        // The three the renderer composes itself, from the declared contract: the parameter
        // and the rule, the resource type, and a constant. None can carry what it was handed.
        List<LLMReadable> composed = List.of(
                new SystemException("component", "internal detail " + CANARY, null),
                new InvalidInputException("query", CANARY, "must be a term"),
                new ResourceNotFoundException("Compound", CANARY));
        for (LLMReadable failure : composed) {
            String rendered = McpErrorText.of(failure);
            assertFalse(rendered.isBlank(), failure.getClass().getSimpleName());
            assertFalse(rendered.contains(CANARY),
                    () -> failure.getClass().getSimpleName() + " forwarded what it was handed: " + rendered);
        }
    }

    @Test
    void anUpstreamFailureNamesTheEndpointAndTheStatusAndNotTheCallersArguments() {
        // What an upstream failure may say is the endpoint, the status and the service's own
        // answer: a model judging whether to retry needs all three. What it may never say is
        // what the caller supplied. A client builds its request target by appending a query
        // string carrying exactly that, so the endpoint an error is built with is the path
        // alone, cut where the query begins.
        String rendered = McpErrorText.of(new Http400Exception("ClinicalTrials.gov",
                "/studies?query.term=" + CANARY + "&pageSize=20", "the upstream explained itself"));
        assertFalse(rendered.contains(CANARY), () -> "the caller's own query came back: " + rendered);
        assertTrue(rendered.contains("/studies"), () -> "the endpoint is ours to name: " + rendered);
        assertTrue(rendered.contains("the upstream explained itself"),
                () -> "the service's answer is what a model needs to judge the failure: " + rendered);
    }

    // ---- the strict inverse: only a declared optional's null is an omission ----

    @Test
    void aNullUnderAnUndeclaredNameIsAnUndeclaredPropertyOnTheStrictPath() {
        // The inverse removes a null only where the canonical schema declares an optional. A
        // null under an invented name is still an undeclared property, refused by our gate
        // with the accepted names and never the invented one.
        Map<String, Object> arguments = inTheSpellingOf(SchemaDialect.OPENAI_STRICT, new LinkedHashMap<>(Map.of("text", "fine " + CANARY)));
        arguments.put("admin" + KEY_CANARY, null);
        McpSchema.CallToolResult result = callStrictTool(SchemaDialect.OPENAI_STRICT, arguments);
        assertEquals(Boolean.TRUE, result.isError(), () -> "accepted an undeclared property because its value was null: " + text(result));
        assertEquals(Boolean.TRUE, result.meta().get(McpToolServer.META_CORRECTABLE), "refused by this process, not by the SDK");
        String body = NucleoJsonSerializer.write(result);
        assertFalse(body.contains(KEY_CANARY), "the invented name came back: " + body);
        assertFalse(body.contains(CANARY), body);
        assertTrue(text(result).contains("not declared"), text(result));
    }

    @Test
    void aNullOnARequiredPropertyIsAMissingPropertyOnTheStrictPath() {
        Map<String, Object> arguments = inTheSpellingOf(SchemaDialect.OPENAI_STRICT, new LinkedHashMap<>());
        arguments.put("text", null);
        McpSchema.CallToolResult result = callStrictTool(SchemaDialect.OPENAI_STRICT, arguments);
        assertEquals(Boolean.TRUE, result.isError());
        assertEquals(Boolean.TRUE, result.meta().get(McpToolServer.META_CORRECTABLE));
        assertTrue(text(result).contains("'text'"), "the required parameter is ours to name: " + text(result));
    }

    @Test
    void aNullOnANestedOptionalIsAnOmissionOnTheStrictPath() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("note", null);
        Map<String, Object> arguments = inTheSpellingOf(SchemaDialect.OPENAI_STRICT, new LinkedHashMap<>(Map.of("text", "fine")));
        arguments.put("nested", nested);
        McpSchema.CallToolResult result = callStrictTool(SchemaDialect.OPENAI_STRICT, arguments);
        assertNotEquals(Boolean.TRUE, result.isError(), () -> "the inverse follows the schema below the root: " + text(result));
    }

    @Test
    void aNullUnderAnUndeclaredNestedNameIsRefusedOnTheStrictPath() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("note", "n");
        nested.put("admin" + KEY_CANARY, null);
        Map<String, Object> arguments = inTheSpellingOf(SchemaDialect.OPENAI_STRICT, new LinkedHashMap<>(Map.of("text", "fine " + CANARY)));
        arguments.put("nested", nested);
        McpSchema.CallToolResult result = callStrictTool(SchemaDialect.OPENAI_STRICT, arguments);
        assertEquals(Boolean.TRUE, result.isError(), () -> text(result));
        assertEquals(Boolean.TRUE, result.meta().get(McpToolServer.META_CORRECTABLE));
        String body = NucleoJsonSerializer.write(result);
        assertFalse(body.contains(KEY_CANARY), body);
        assertFalse(body.contains(CANARY), body);
    }

    @Test
    void aStrictCallerThatOmitsANestedOptionalGetsARefusalThisProcessComposed() {
        // The strict spelling closes and requires at every depth, so an empty nested object
        // omits the nested optional. Same rule as at the root: the refusal is this process's.
        Map<String, Object> arguments = inTheSpellingOf(SchemaDialect.OPENAI_STRICT, new LinkedHashMap<>(Map.of("text", "fine " + CANARY)));
        arguments.put("nested", Map.of());
        McpSchema.CallToolResult result = callStrictTool(SchemaDialect.OPENAI_STRICT, arguments);
        assertEquals(Boolean.TRUE, result.isError(), () -> "accepted a nested document the strict spelling did not publish: " + text(result));
        assertFalse(lastWire.contains(CANARY), lastWire);
        assertNotNull(result.meta(), () -> "a refusal this process composed carries its marker: " + lastWire);
        assertEquals(Boolean.TRUE, result.meta().get(McpToolServer.META_CORRECTABLE), () -> lastWire);
    }

    @Test
    void aMetaThatIsNotAnObjectIsParamsInAnUnreadableShape() {
        // The one params key this interposer does not read itself beyond the dialect. The
        // SDK converts params before any handler and its parser quotes what it could not
        // read, so the shape is judged here and gets the constant answer every unreadable
        // call gets - before any dialect is even looked for inside it.
        int id = ids.incrementAndGet();
        McpSchema.JSONRPCResponse refused = transport.handler.handleRequest(as(CONSUMER),
                new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, McpSchema.METHOD_TOOLS_CALL, id,
                        Map.of("name", "mcp_strict", "arguments", Map.of("text", "fine"), "_meta", "progress " + CANARY))).block();
        McpSchema.JSONRPCResponse unknown = transport.handler.handleRequest(as(CONSUMER),
                new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, McpSchema.METHOD_TOOLS_CALL, id,
                        Map.of("name", "mcp_" + KEY_CANARY, "arguments", Map.of()))).block();
        String wire = NucleoJsonSerializer.write(refused);
        assertEquals(NucleoJsonSerializer.write(unknown), wire, "the one constant answer for an unreadable call");
        assertFalse(wire.contains(CANARY), wire);
    }

    @Test
    void anUnreadableInitializeStillEchoesTheSdksParserUntilTheSdkIsFixed() {
        // Holds a defect in the MCP Java SDK 2.0.1 in place, not a rule of this package.
        // initialize carries nothing for this process to judge, so it is delegated whole; the
        // SDK converts its params inside its own pipeline and answers a caller's mistake with
        // the internal error code, carrying its parser's complaint, which quotes the value and
        // names an SDK class. Two things a boundary owes a stranger are broken by that: a
        // caller's mistake reported as our internal failure, and the caller's own text handed
        // back to it.
        //
        // Pinned green rather than left red, because a red test nobody can fix teaches
        // everyone to ignore red, and because a rule we do not enforce has no business
        // reading as one here. WHEN THIS FAILS the SDK has changed: read the response, and if
        // it now refuses with invalid params and says nothing of the request, delete this test
        // and nodeploy/mcp-java-sdk-initialize-defect.md together. It is not worked around: a
        // wrapper would have to pre-parse the params of every delegated method and reproduce
        // the SDK's acceptance rules, which we would then own forever.
        for (SchemaDialect dialect : SchemaDialect.values()) {
            McpSchema.JSONRPCResponse response = send(dialect, as(CONSUMER), McpSchema.METHOD_INITIALIZE,
                    Map.of("protocolVersion", "2025-06-18", "capabilities", "caps " + CANARY,
                            "clientInfo", Map.of("name", "test-client", "version", "0")));
            assertNotNull(response.error(), dialect + ": an unreadable initialize is refused");
            assertEquals(McpSchema.ErrorCodes.INTERNAL_ERROR, response.error().code(),
                    dialect + ": the SDK still answers a caller's mistake as an internal failure");
            assertTrue(NucleoJsonSerializer.write(response).contains(CANARY),
                    dialect + ": the SDK still quotes the request back; see the report under nodeploy");
        }
    }

    @Test
    void aStrictCallerThatOmitsAnOptionalGetsARefusalThisProcessComposed() {
        // In the strict spelling the published schema requires every property. A caller that
        // omits one has not sent what it read; the refusal it gets is one this
        // process composed, carrying the correctable marker and naming the missing property.
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("text", "fine " + CANARY);
        McpSchema.CallToolResult result = callStrictTool(SchemaDialect.OPENAI_STRICT, arguments);
        assertEquals(Boolean.TRUE, result.isError(), () -> "accepted a document the strict spelling did not publish: " + text(result));
        String body = NucleoJsonSerializer.write(result);
        assertFalse(body.contains(CANARY), body);
        assertNotNull(result.meta(), () -> "a refusal this process composed carries its marker: " + body);
        assertEquals(Boolean.TRUE, result.meta().get(McpToolServer.META_CORRECTABLE), () -> "a caller that can fix its input must be told so: " + body);
    }

    // ---- what each dialect publishes, against its own rules ----

    @TestFactory
    Stream<DynamicTest> eachDialectPublishesItsRules() {
        // The canonical form keeps the harness keywords by design; every other dialect drops
        // them, and each narrows the vocabulary the way its vendor's page says.
        return dialects().map(dialect -> DynamicTest.dynamicTest(dialect.name(), () -> {
            McpSchema.Tool strict = listed(dialect, "mcp_strict");
            McpSchema.Tool tree = listed(dialect, "mcp_tree");
            String input = NucleoJsonSerializer.write(strict.inputSchema());
            String output = NucleoJsonSerializer.write(tree.outputSchema());
            if (dialect != SchemaDialect.CANONICAL) {
                assertFalse(input.contains(NucleoSchemaKeywords.PREFIX), dialect + " carries harness keywords: " + input);
            }
            // A switch EXPRESSION over the enum, deliberately: an eighth dialect added to
            // SchemaDialect does not compile until its rules are written here. A statement
            // would let a new one through silently, exercised by the generic factories and
            // pinned by nothing.
            String pinned = switch (dialect) {
                case CANONICAL -> {
                    assertEquals(Boolean.FALSE, strict.inputSchema().get("additionalProperties"), "the published contract is closed");
                    Map<?, ?> nested = (Map<?, ?>)((Map<?, ?>)strict.inputSchema().get("properties")).get("nested");
                    assertEquals(Boolean.FALSE, nested.get("additionalProperties"), "closed at every object, as the gate judges: " + nested);
                    assertEquals(List.of("text"), strict.inputSchema().get("required"));
                    assertTrue(output.contains("$defs"), "a recursive output keeps its definitions: " + output);
                    yield "closed, honest about what is required, references kept";
                }
                case FLAT, ANTHROPIC_STRICT, BEDROCK_STRICT, NOVA -> {
                    assertFalse(output.contains("\"$ref\""), dialect + " publishes a reference: " + output);
                    assertFalse(output.contains("\"$defs\""), dialect + " publishes definitions: " + output);
                    yield "no references anywhere";
                }
                case OPENAI_STRICT -> {
                    List<?> required = (List<?>)strict.inputSchema().get("required");
                    assertTrue(required.containsAll(List.of("text", "count", "loud", "tags", "nested", "when")), required.toString());
                    assertEquals(Boolean.FALSE, strict.inputSchema().get("additionalProperties"));
                    assertTrue(output.contains("$defs"), "references and recursion are kept: " + output);
                    yield "every property required, objects closed, recursion kept";
                }
                case GEMINI -> {
                    assertFalse(output.contains("\"$ref\""), output);
                    assertTrue(output.contains("\"ref\""), "the reference keyword without the dollar sign: " + output);
                    assertFalse(input.contains("\"title\""), input);
                    yield "references without the dollar sign, no titles";
                }
            };
            assertFalse(pinned.isBlank());
            if (dialect == SchemaDialect.NOVA) {
                assertEquals(Set.of("type", "properties", "required"), strict.inputSchema().keySet(), "three root keys and no more");
                assertFalse(input.contains("\"title\""), input);
            }
        }));
    }

    @TestFactory
    Stream<DynamicTest> aStructuredResultValidatesUnderEveryDialectsOutputSchema() {
        // The SDK validates structured content against the output schema the request's
        // dialect published. A dialect that breaks a cycle or renames a keyword must still
        // describe what the tool returns, or every call naming it fails after the tool ran.
        //
        // That check is weaker than it looks on GEMINI, whose references are spelled without
        // the dollar sign: a JSON Schema validator reads those as unknown keywords carrying
        // no constraint, so anything passes. So the result is judged again here against the
        // canonical output schema, by our own checker, which reads both spellings.
        ToolProvider tree = ClassToolProvider.of(TreeTool.class);
        JsonNode canonicalOutput = McpSchemaPublisher.outputSchemaOf(tree);
        return dialects().map(dialect -> DynamicTest.dynamicTest(dialect.name(), () -> {
            McpSchema.CallToolResult result = call(dialect, CONSUMER, "mcp_tree", Map.of());
            assertNotEquals(Boolean.TRUE, result.isError(), () -> dialect + ": " + text(result));
            assertNotNull(result.structuredContent(), dialect + " publishes an output schema, so the result rides structured too");
            JsonNode structured = McpBoundaryJson.TREE_MAPPER.valueToTree(result.structuredContent());
            assertDoesNotThrow(() -> McpInputGate.admit("mcp_tree", canonicalOutput, structured),
                    dialect + " shipped structured content the canonical output schema does not describe: " + structured);
        }));
    }

    // ---- the protocol seams hold in every dialect ----

    @TestFactory
    Stream<DynamicTest> anUngrantedToolAndANonexistentOneAnswerIdenticallyOnEveryDialect() {
        // Both are the interposer's one constant JSON-RPC error, compared whole after the
        // request id is taken out of the comparison by sending both with the same one.
        return dialects().map(dialect -> DynamicTest.dynamicTest(dialect.name(), () -> {
            int id = ids.incrementAndGet();
            Map<String, Object> meta = Map.of(SchemaDialect.META_KEY, dialect.wireName());
            McpSchema.JSONRPCResponse ungranted = transport.handler.handleRequest(as(STRANGER),
                    new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, McpSchema.METHOD_TOOLS_CALL, id,
                            Map.of("name", "mcp_strict", "arguments", Map.of("text", CANARY), "_meta", meta))).block();
            McpSchema.JSONRPCResponse nonexistent = transport.handler.handleRequest(as(CONSUMER),
                    new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, McpSchema.METHOD_TOOLS_CALL, id,
                            Map.of("name", "mcp_" + KEY_CANARY, "arguments", Map.of("text", CANARY), "_meta", meta))).block();
            String first = NucleoJsonSerializer.write(ungranted);
            String second = NucleoJsonSerializer.write(nonexistent);
            assertEquals(first, second, "grants cannot be probed in " + dialect);
            assertFalse(first.contains(CANARY) || first.contains(KEY_CANARY), first);
        }));
    }

    @TestFactory
    Stream<DynamicTest> anUnauthenticatedCallerListsNothingOnEveryDialect() {
        return dialects().map(dialect -> DynamicTest.dynamicTest(dialect.name(), () -> {
            McpSchema.JSONRPCResponse response = send(dialect, McpTransportContext.create(Map.of()), McpSchema.METHOD_TOOLS_LIST, Map.of());
            assertNull(response.error());
            assertTrue(((McpSchema.ListToolsResult)response.result()).tools().isEmpty(), dialect.name());
        }));
    }

    @TestFactory
    Stream<DynamicTest> anUnknownMethodIsRefusedTheSameWayOnEveryDialect() {
        String canonical = NucleoJsonSerializer.write(send(SchemaDialect.CANONICAL, as(CONSUMER), "resources/" + KEY_CANARY, Map.of()).error());
        return dialects().map(dialect -> DynamicTest.dynamicTest(dialect.name(), () -> {
            McpSchema.JSONRPCResponse response = send(dialect, as(CONSUMER), "resources/" + KEY_CANARY, Map.of());
            assertNotNull(response.error(), dialect.name());
            assertEquals(McpSchema.ErrorCodes.METHOD_NOT_FOUND, response.error().code());
            assertEquals(canonical, NucleoJsonSerializer.write(response.error()), "one answer in every dialect");
            assertFalse(NucleoJsonSerializer.write(response.error()).contains(KEY_CANARY), "the invented method is the caller's text");
        }));
    }

    @Test
    void everyDialectTheEnumDeclaresIsServedThroughTheOneTransport() {
        // The guarantee the whole redesign exists for. A host wires one transport and takes
        // no decision about dialects, so every value the enum declares must be answerable
        // here: listed with the consumer's full grant, callable, and reported as itself.
        // Driven from values(), so a dialect that is added and forgotten fails this.
        assertEquals(7, SchemaDialect.values().length, "a dialect was added or removed; the rules below and the docs must follow");
        Set<String> everyGrantedTool = new TreeSet<>(List.of("mcp_strict", "mcp_tree", "mcp_refs", "mcp_plant", "mcp_ping", "mcp_faulty"));
        for (SchemaDialect dialect : SchemaDialect.values()) {
            McpSchema.JSONRPCResponse listing = send(dialect, as(CONSUMER), McpSchema.METHOD_TOOLS_LIST, Map.of());
            assertNull(listing.error(), dialect + " could not list: " + NucleoJsonSerializer.write(listing));
            Set<String> listedNames = new TreeSet<>();
            ((McpSchema.ListToolsResult)listing.result()).tools().forEach(tool -> listedNames.add(tool.name()));
            assertTrue(listedNames.containsAll(everyGrantedTool),
                    dialect + " listed fewer tools than the consumer may call: " + listedNames);
            McpSchema.CallToolResult called = call(dialect, CONSUMER, "mcp_ping", Map.of());
            assertNotEquals(Boolean.TRUE, called.isError(), dialect + " could not run a call: " + text(called));
        }
    }

    @Test
    void noDialectIsAStubThatQuietlyRepublishesTheCanonicalForm() {
        // A dialect whose transform does nothing behaves exactly like canonical and would
        // pass every generic test in this class. So each must respell something somewhere in
        // the catalog. Somewhere rather than everywhere on purpose: a schema with no
        // recursion and no harness keywords is already flat, and FLAT rendering it unchanged
        // is correct, not a stub.
        for (SchemaDialect dialect : SchemaDialect.values()) {
            boolean respellsSomething = false;
            for (ToolProvider provider : server.getCatalog().all()) {
                JsonNode canonical = McpSchemaPublisher.canonicalInputSchema(provider);
                if (!NucleoJsonSerializer.writeCompact(SchemaDialect.CANONICAL.inputSchema(canonical))
                        .equals(NucleoJsonSerializer.writeCompact(dialect.inputSchema(canonical)))) {
                    respellsSomething = true;
                    break;
                }
            }
            if (dialect == SchemaDialect.CANONICAL) {
                assertFalse(respellsSomething, "the canonical dialect is the identity on every tool");
            }
            else {
                assertTrue(respellsSomething,
                        dialect + " renders every tool in the catalog exactly as canonical does, so it narrows nothing");
            }
        }
    }

    @Test
    void initializeNamesTheParameterAndEveryDialect() {
        // The one place a client learns the selector without reading our docs: the key, the
        // accepted values, and that omission means canonical.
        Map<String, Object> params = Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(),
                "clientInfo", Map.of("name", "test-client", "version", "0"));
        McpSchema.InitializeResult initialized = (McpSchema.InitializeResult)send(SchemaDialect.CANONICAL, as(CONSUMER), McpSchema.METHOD_INITIALIZE, params).result();
        assertTrue(initialized.instructions().contains(SchemaDialect.META_KEY), initialized.instructions());
        for (SchemaDialect each : SchemaDialect.values()) {
            assertTrue(initialized.instructions().contains(each.wireName()), "does not name " + each + ": " + initialized.instructions());
        }
    }
}
