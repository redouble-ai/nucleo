/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.server;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.registry.*;
import com.fasterxml.jackson.databind.*;
import io.modelcontextprotocol.common.*;
import io.modelcontextprotocol.server.*;
import io.modelcontextprotocol.spec.*;
import org.slf4j.*;
import reactor.core.publisher.*;

import java.util.*;

/**
 * The fail-closed security decorator around the host's stateless transport. The SDK
 * server lists every registered tool unconditionally and has no per-request hook, so
 * per-consumer admission is enforced one seam below it: the handler the server installs
 * on the transport is wrapped, and every JSON-RPC request is judged before the SDK sees
 * it.
 * <ul>
 *   <li>Request methods are whitelisted: {@code initialize}, {@code ping},
 *       {@code tools/list}, {@code tools/call}. Anything else is answered with the SDK's
 *       own method-not-found shape without being delegated, so a capability the SDK
 *       grows later never opens by default.</li>
 *   <li>{@code tools/list} is delegated and its result filtered to the tools the policy
 *       admits for the resolved consumer, then republished in the dialect the request
 *       named. An unauthenticated request lists nothing. A result of any shape other than
 *       the SDK's list result lists nothing and is logged: SDK drift can never leak the
 *       catalog.</li>
 *   <li>{@code tools/call} is delegated only when the resolved consumer is admitted to
 *       the named tool; any other case is answered with a response field-identical to
 *       the SDK's unknown-tool error, so an ungranted tool is indistinguishable from a
 *       nonexistent one. The delegated request carries the resolved consumer on its
 *       transport context under {@link #CONSUMER_KEY}; the call handler reads that and
 *       never resolves again, so one decision is made in one place.</li>
 *   <li>An admitted call's arguments are judged here, before the SDK, and twice: against
 *       the schema the request's dialect published, then against the canonical schema,
 *       with {@link SchemaDialect#arguments} between them. This is the only judge, which
 *       is why the server switches the SDK's own input validation off. The dialect rides
 *       to the handler under {@link #DIALECT_KEY}.</li>
 * </ul>
 * A request names its dialect in {@code _meta} under {@link SchemaDialect#META_KEY}, or
 * the host puts the same key on the transport context from wherever its clients can reach;
 * the request's own naming wins, an omission means canonical, and a name the enum does not
 * declare is refused with the accepted ones.
 * <p>
 * Notifications delegate unchanged. The host transport's lifecycle methods delegate
 * unchanged.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-02)
 */
public final class McpTransportInterposer implements McpStatelessServerTransport {
    private static final Logger log = LoggerFactory.getLogger(McpTransportInterposer.class);
    /**
     * Transport-context key under which an admitted {@code tools/call} carries the resolved
     * consumer to the call handler.
     */
    public static final String CONSUMER_KEY = "ai.redouble.mcp.consumer";
    /**
     * Transport-context key under which an admitted {@code tools/call} carries the dialect
     * the request named, so the handler can turn the arguments canonical before parsing.
     */
    public static final String DIALECT_KEY = "ai.redouble.mcp.dialect";
    private static final String NAME = "name";
    private static final String ARGUMENTS = "arguments";
    private static final String META = "_meta";
    private final McpStatelessServerTransport inner;
    private final McpToolCatalog catalog;
    private final McpConsumerResolver resolver;
    private final McpAccessPolicy policy;

    public McpTransportInterposer(McpStatelessServerTransport inner, McpToolCatalog catalog, McpConsumerResolver resolver, McpAccessPolicy policy) {
        this.inner = inner;
        this.catalog = catalog;
        this.resolver = resolver;
        this.policy = policy;
    }

    /**
     * The dialect this request named, or null for a name that is no dialect. The parameter
     * is optional and has two doors into one slot: the {@code _meta} key
     * {@link SchemaDialect#META_KEY} on the request's params, for a caller that speaks the
     * protocol, and the transport context under the same key, for whatever the host's
     * transport forwards (typically a {@code dialect} query parameter, for a client
     * whose only configurable surface is its URL). The request's own naming wins, absence
     * means canonical, and an unreadable {@code _meta} never reaches this method - the
     * shape check refuses it first.
     */
    private static SchemaDialect dialectOf(McpTransportContext context, Object params) {
        Object named = null;
        Object meta = metaOf(params);
        if (meta instanceof Map<?, ?> map) {
            named = map.get(SchemaDialect.META_KEY);
        }
        else if (meta instanceof JsonNode node && node.get(SchemaDialect.META_KEY) != null) {
            named = node.get(SchemaDialect.META_KEY).asText();
        }
        if (named == null) {
            named = context.get(SchemaDialect.META_KEY);
        }
        if (named == null) {
            return SchemaDialect.CANONICAL;
        }
        return named instanceof String name ? SchemaDialect.named(name) : null;
    }

    /** The refusal an unknown dialect name gets: the accepted names, which are ours, and nothing the caller sent. */
    private static McpSchema.JSONRPCResponse unknownDialect(Object id) {
        return error(id, McpSchema.ErrorCodes.INVALID_PARAMS,
                "Unknown dialect. Accepted: " + SchemaDialect.wireNames() + ", or omit the parameter for canonical.", null);
    }

    @Override
    public void setMcpHandler(McpStatelessServerHandler handler) {
        inner.setMcpHandler(new GatingHandler(handler));
    }

    @Override
    public Mono<Void> closeGracefully() {
        return inner.closeGracefully();
    }

    @Override
    public void close() {
        inner.close();
    }

    @Override
    public List<String> protocolVersions() {
        return inner.protocolVersions();
    }

    /**
     * The transport context of an admitted call: the resolved consumer under
     * {@link #CONSUMER_KEY}, the path's dialect under {@link #DIALECT_KEY}, everything else
     * from the host's context.
     */
    private record ConsumerContext(McpTransportContext wrapped, McpConsumer consumer, SchemaDialect dialect) implements McpTransportContext {
        @Override
        public Object get(String key) {
            if (CONSUMER_KEY.equals(key)) {
                return consumer;
            }
            if (DIALECT_KEY.equals(key)) {
                return dialect;
            }
            return wrapped.get(key);
        }
    }

    private final class GatingHandler implements McpStatelessServerHandler {
        private final McpStatelessServerHandler delegate;

        private GatingHandler(McpStatelessServerHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public Mono<McpSchema.JSONRPCResponse> handleRequest(McpTransportContext context, McpSchema.JSONRPCRequest request) {
            return switch (request.method()) {
                case McpSchema.METHOD_INITIALIZE, McpSchema.METHOD_PING -> delegate.handleRequest(context, request);
                case McpSchema.METHOD_TOOLS_LIST -> listTools(context, request);
                case McpSchema.METHOD_TOOLS_CALL -> callTool(context, request);
                default -> {
                    // The method name is the caller's own text, so it is neither returned nor logged.
                    log.warn("McpTransportInterposer: refused a request for a method outside the four this server answers");
                    yield Mono.just(error(request.id(), McpSchema.ErrorCodes.METHOD_NOT_FOUND,
                            "Missing handler for request type", null));
                }
            };
        }

        @Override
        public Mono<Void> handleNotification(McpTransportContext context, McpSchema.JSONRPCNotification notification) {
            return delegate.handleNotification(context, notification);
        }

        private Mono<McpSchema.JSONRPCResponse> listTools(McpTransportContext context, McpSchema.JSONRPCRequest request) {
            McpConsumer consumer = resolveConsumer(context, McpSchema.METHOD_TOOLS_LIST);
            if (consumer == null) {
                return Mono.just(emptyListing(request.id()));
            }
            if (!metaIsReadable(request.params())) {
                log.warn("McpTransportInterposer: tools/list with a _meta that is not an object; refused");
                return Mono.just(emptyListing(request.id()));
            }
            SchemaDialect dialect = dialectOf(context, request.params());
            if (dialect == null) {
                return Mono.just(unknownDialect(request.id()));
            }
            return delegate.handleRequest(context, request).map(response -> filterListing(response, consumer, dialect));
        }

        private McpSchema.JSONRPCResponse filterListing(McpSchema.JSONRPCResponse response, McpConsumer consumer, SchemaDialect dialect) {
            if (response.error() != null) {
                return response;
            }
            if (!(response.result() instanceof McpSchema.ListToolsResult listing)) {
                log.error("McpTransportInterposer: tools/list produced {} instead of a ListToolsResult; listing nothing to {}",
                        describe(response.result()),
                        consumer.name());
                return emptyListing(response.id());
            }
            List<McpSchema.Tool> admitted = new ArrayList<>();
            for (McpSchema.Tool tool : listing.tools()) {
                if (admits(consumer, tool.name())) {
                    // The SDK's registry holds one rendering, the canonical one; a request
                    // that named another dialect gets each admitted tool republished in that
                    // spelling, from the same provider the registry entry came from.
                    ToolProvider provider = dialect == SchemaDialect.CANONICAL ? null : catalog.byName(tool.name());
                    admitted.add(provider == null ? tool : McpSchemaPublisher.publish(provider, dialect));
                }
            }
            McpSchema.ListToolsResult filtered = new McpSchema.ListToolsResult(admitted, listing.nextCursor(), listing.meta());
            return new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, response.id(), filtered, null);
        }

        private Mono<McpSchema.JSONRPCResponse> callTool(McpTransportContext context, McpSchema.JSONRPCRequest request) {
            String toolName = toolNameOf(request.params());
            if (toolName == null) {
                log.warn("McpTransportInterposer: tools/call without a readable tool name in params of type {}; refused", describe(request.params()));
                return Mono.just(unknownTool(request.id()));
            }
            // The name is not passed to the log line: an invented one is the caller's text,
            // and an unauthenticated request would otherwise persist whatever it asked for.
            McpConsumer consumer = resolveConsumer(context, McpSchema.METHOD_TOOLS_CALL);
            if (consumer == null || !admits(consumer, toolName)) {
                return Mono.just(unknownTool(request.id()));
            }
            if (!metaIsReadable(request.params())) {
                // Params in an unreadable shape get the one constant answer. Delegated, the
                // SDK would convert them eagerly and its parser's complaint, quoting the
                // value, would leave as a system failure.
                log.warn("McpTransportInterposer: tools/call with a _meta that is not an object; refused");
                return Mono.just(unknownTool(request.id()));
            }
            SchemaDialect dialect = dialectOf(context, request.params());
            if (dialect == null) {
                return Mono.just(unknownDialect(request.id()));
            }
            // Input admission belongs here, ahead of the SDK, for the same reason tool
            // admission does: the SDK validates against the published schema itself, and
            // its refusal quotes the payload and carries none of our correctable marker.
            // Judging first means the refusal a consumer sees is one this process composed.
            McpSchema.JSONRPCResponse refusal = refuseUnacceptableInput(request, toolName, dialect);
            if (refusal != null) {
                return Mono.just(refusal);
            }
            return delegate.handleRequest(new ConsumerContext(context, consumer, dialect), request);
        }

        /**
         * The verdict on this call's arguments as a response, or null to proceed.
         *
         * <p>Two judgements, both this process's. First the document as it arrived, against
         * the schema the request's dialect publishes: a caller that obeyed what it fetched
         * passes, and one that did not is told so in our words rather than a validator's we
         * did not write. Then the same arguments in canonical form, against the canonical
         * schema, which carries every constraint that spelling could not say - a bound
         * dropped for Gemini, a format Nova has no keyword for. A dialect narrows the
         * description; it never widens what is accepted.
         */
        private McpSchema.JSONRPCResponse refuseUnacceptableInput(McpSchema.JSONRPCRequest request, String toolName, SchemaDialect dialect) {
            ToolProvider provider = catalog.byName(toolName);
            try {
                JsonNode wire = argumentsOf(request.params());
                JsonNode canonical = McpSchemaPublisher.canonicalInputSchema(provider);
                McpInputGate.admit(toolName, dialect.inputSchema(canonical), wire);
                McpInputGate.admit(toolName, canonical, dialect.arguments(wire, canonical));
                return null;
            }
            catch (InvalidInputException refused) {
                log.warn("McpTransportInterposer: {} refused input: {}", toolName, refused.getValidationRule());
                return new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(),
                        McpSchema.CallToolResult.builder()
                                .addTextContent(refused.getLLMMessage())
                                .isError(true)
                                .meta(Map.of(McpToolServer.META_CORRECTABLE, true))
                                .build(),
                        null);
            }
        }

        /**
         * The consumer behind a request, or null when unauthenticated: a null result and a
         * throwing resolver both fail closed, logged.
         */
        private McpConsumer resolveConsumer(McpTransportContext context, String what) {
            McpConsumer consumer;
            try {
                consumer = resolver.resolve(context);
            }
            catch (Exception e) {
                log.error("McpTransportInterposer: consumer resolution failed for {}; refused", what, e);
                return null;
            }
            if (consumer == null) {
                log.warn("McpTransportInterposer: unauthenticated {}; refused", what);
                return null;
            }
            return consumer;
        }

        private boolean admits(McpConsumer consumer, String toolName) {
            ToolProvider provider = catalog.byName(toolName);
            if (provider == null) {
                return false;
            }
            try {
                return policy.admits(consumer, provider);
            }
            catch (Exception e) {
                log.error("McpTransportInterposer: access policy failed for consumer {} on tool {}; refused", consumer.name(), toolName, e);
                return false;
            }
        }
    }

    /**
     * The tool name of a {@code tools/call}, read from the two shapes the SDK's transports
     * produce for {@code params}; any other shape yields null and the call is refused.
     */
    static String toolNameOf(Object params) {
        if (params instanceof Map<?, ?> map && map.get(NAME) instanceof String name) {
            return name;
        }
        if (params instanceof JsonNode node && node.path(NAME).isTextual()) {
            return node.path(NAME).textValue();
        }
        return null;
    }

    /**
     * Whether the call's {@code _meta}, if present, is an object as the protocol declares.
     * The two params keys this interposer does not read itself are {@code arguments}, which
     * the gate judges, and {@code _meta}, which only the SDK reads; a wrong shape there must
     * be refused here, because the SDK converts params before any handler and its parser
     * quotes what it could not read.
     */
    private static boolean metaIsReadable(Object params) {
        Object meta = metaOf(params);
        if (meta == null) {
            return true;
        }
        return meta instanceof Map<?, ?> || (meta instanceof JsonNode node && (node.isObject() || node.isNull()));
    }

    /** The params' {@code _meta}, in the two shapes the SDK's transports produce, or null. */
    private static Object metaOf(Object params) {
        if (params instanceof Map<?, ?> map) {
            return map.get(META);
        }
        if (params instanceof JsonNode node) {
            return node.get(META);
        }
        return null;
    }

    /**
     * The single answer to every tool a caller may not call: one that does not exist, one it
     * is not granted, and one it named unreadably. Constant, so the three are one response
     * and a grant cannot be probed - and carrying no part of what was asked for, since the
     * name a caller invented is the caller's own text.
     */
    static McpSchema.JSONRPCResponse unknownTool(Object id) {
        return error(id, McpSchema.ErrorCodes.INVALID_PARAMS, "Unknown tool: invalid_tool_name", "Tool not found");
    }

    /**
     * The arguments of a {@code tools/call}, in the two shapes the SDK's transports produce
     * for {@code params}. An absent or unreadable {@code arguments} is an empty object, and
     * the gate judges it against the schema like any other.
     *
     * <p>Nothing is removed on the way in. {@code artifact_refs} is not published, so a
     * consumer that sends it is sending an undeclared parameter and is refused like any
     * other - tolerating it here would accept what the published schema says we do not.
     */
    static JsonNode argumentsOf(Object params) {
        Object arguments = null;
        if (params instanceof Map<?, ?> map) {
            arguments = map.get(ARGUMENTS);
        }
        else if (params instanceof JsonNode node) {
            arguments = node.has(ARGUMENTS) ? node.get(ARGUMENTS) : null;
        }
        if (arguments == null) {
            return NucleoJsonSerializer.createObjectNode();
        }
        return arguments instanceof JsonNode node ? node : McpBoundaryJson.TREE_MAPPER.valueToTree(arguments);
    }

    private static McpSchema.JSONRPCResponse emptyListing(Object id) {
        return new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, id, new McpSchema.ListToolsResult(List.of(), null, null), null);
    }

    private static McpSchema.JSONRPCResponse error(Object id, int code, String message, Object data) {
        return new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, id, null,
                new McpSchema.JSONRPCResponse.JSONRPCError(code, message, data));
    }

    private static String describe(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }
}
