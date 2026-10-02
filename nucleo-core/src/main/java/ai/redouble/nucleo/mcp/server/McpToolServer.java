/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.server;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.artifacts.tools.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.registry.*;
import ai.redouble.nucleo.tools.thinking.*;
import com.fasterxml.jackson.databind.*;
import io.modelcontextprotocol.common.*;
import io.modelcontextprotocol.json.*;
import io.modelcontextprotocol.server.*;
import io.modelcontextprotocol.spec.*;
import org.slf4j.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Serves exposable tools over MCP through a host-supplied stateless transport.
 * Framework-agnostic: the host provides the transport, the consumer resolution, and the
 * access policy; this class provides the catalog, the per-consumer gate
 * ({@link McpTransportInterposer}), the schema publication, and the call handler that
 * runs a tool as an ordinary root job.
 * <p>
 * A served tool is the root of its own flow, submitted through the public dispatcher
 * door from the request thread under {@code Job.workflow(consumer, "mcp")}. Everything
 * that protects a tool is therefore the tool's own and the dispatcher's: input and
 * output guardrails run at dispatch, the consumer name is the principal the auth and
 * admission rungs judge, and a scoped root orchestrator seals its own scope for its
 * descendants. The handler submits and waits; it enforces nothing itself.
 * <p>
 * The call handler runs on the transport's request thread ({@code immediateExecution}):
 * the servlet transport blocks on the handler anyway, so the container's thread pool is
 * the bound at the door and the dispatcher's admission is the bound on the work; the
 * server itself neither queues nor refuses a call. A call that outlives the timeout
 * cancels its whole workflow, so sub-agents die with the root; cancellation is
 * cooperative, so a leaf mid-call finishes that call. Any failure leaving the handler
 * body becomes an error result carrying the framework's LLM-readable text; a
 * correctable failure is flagged as such in the result's metadata.
 * <p>
 * Results ship full-form: the caller has no artifact registry, so the whole output
 * object, a thinker's reasoning fields included, is serialized as one text block.
 * <p>
 * One endpoint serves every {@link SchemaDialect}, because a request names the spelling it
 * wants rather than the host mounting it. Arguments and results are identical in all of
 * them, and every call is judged against the canonical schema, so nothing about a
 * deployment decides which dialects exist.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-02)
 */
public class McpToolServer {
    private static final Logger log = LoggerFactory.getLogger(McpToolServer.class);
    private static final String COMPONENT = "McpToolServer";
    private static final String WORKFLOW_PREFIX = "mcp";
    /** `_meta` flag telling a caller whether a corrected input may succeed. */
    static final String META_CORRECTABLE = "correctable";
    private List<String> scanPackages;
    private List<ToolProvider> providers;
    private McpConsumerResolver consumerResolver;
    private McpAccessPolicy accessPolicy;
    private String serverName;
    private String serverVersion;
    private Duration callTimeout;
    private McpStatelessServerTransport transport;
    private McpToolCatalog catalog;
    private McpStatelessSyncServer server;

    /**
     * Package prefixes scanned for {@code @MCP} tools. Required; a host that serves only
     * explicit providers says so with an empty list.
     */
    public void setScanPackages(List<String> scanPackages) {
        this.scanPackages = scanPackages;
    }

    /**
     * Providers served in addition to the scan. Required; a host that serves only scanned
     * tools says so with an empty list.
     */
    public void setProviders(List<ToolProvider> providers) {
        this.providers = providers;
    }

    public void setConsumerResolver(McpConsumerResolver consumerResolver) {
        this.consumerResolver = consumerResolver;
    }

    public void setAccessPolicy(McpAccessPolicy accessPolicy) {
        this.accessPolicy = accessPolicy;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public void setServerVersion(String serverVersion) {
        this.serverVersion = serverVersion;
    }

    /**
     * How long one call may take before its workflow is cancelled and the caller gets an
     * error result. Required and positive: there is no sensible default for work that may
     * be a single HTTP lookup or a multi-agent investigation.
     */
    public void setCallTimeout(Duration callTimeout) {
        this.callTimeout = callTimeout;
    }

    /**
     * The host's stateless transport, mounted at its one MCP endpoint. Every schema dialect
     * is served through it: the dialect is a per-request parameter
     * ({@link SchemaDialect#META_KEY}), never a property of the transport, so a host wires
     * nothing to serve them all.
     */
    public void setTransport(McpStatelessServerTransport transport) {
        this.transport = transport;
    }

    public McpToolCatalog getCatalog() {
        return catalog;
    }

    public void start() {
        require(scanPackages != null, "scanPackages (an empty list when only explicit providers are served)");
        require(providers != null, "providers (an empty list when only scanned tools are served)");
        require(consumerResolver != null, "consumerResolver");
        require(accessPolicy != null, "accessPolicy");
        require(serverName != null && !serverName.isBlank(), "serverName");
        require(serverVersion != null && !serverVersion.isBlank(), "serverVersion");
        require(callTimeout != null && callTimeout.isPositive(), "a positive callTimeout");
        require(transport != null, "transport");
        require(server == null, "a server that has not been started yet");
        catalog = new McpToolCatalog(scanPackages, providers);
        if (catalog.all().isEmpty()) {
            throw new IllegalStateException(COMPONENT + ": the exposure set is empty; nothing to serve");
        }
        // The SDK's registry holds the canonical rendering; a request that names another
        // dialect gets its listing republished by the interposer and its arguments judged
        // and inverted by that dialect. One server, one endpoint, every spelling.
        List<McpStatelessServerFeatures.SyncToolSpecification> specs = new ArrayList<>();
        for (ToolProvider provider : catalog.all()) {
            specs.add(new McpStatelessServerFeatures.SyncToolSpecification(McpSchemaPublisher.publish(provider, SchemaDialect.CANONICAL),
                    (context, request) -> serve(provider, context, request)));
        }
        McpTransportInterposer interposer = new McpTransportInterposer(transport, catalog, consumerResolver, accessPolicy);
        server = McpServer.sync(interposer)
                .serverInfo(serverName, serverVersion)
                .instructions(dialectInstructions())
                // The interposer has already judged these arguments twice, against the
                // schema the request's dialect publishes and against the canonical one, and
                // composed any refusal itself. Leaving the SDK's validator on would put a
                // second judge behind ours, one whose message quotes the caller and carries
                // none of our correctable marker - the very thing judging first is for.
                .validateToolInputs(false)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .immediateExecution(true)
                .tools(specs)
                .build();
        log.info("{}: serving {} exposable tools as {} {}, dialects {}", COMPONENT, catalog.all().size(), serverName, serverVersion, SchemaDialect.wireNames());
    }

    /**
     * What every {@code initialize} answers about the dialects, so any client learns the
     * parameter and its values. Every schema describes the same contract; only the spelling
     * differs, and a client picks the spelling its model API accepts.
     */
    private String dialectInstructions() {
        return "Tool schemas are published in " + SchemaDialect.values().length
                + " dialects of one contract, selected per request by the optional _meta key '"
                + SchemaDialect.META_KEY + "' (hosts may also forward it from a 'dialect' query parameter). Accepted values: "
                + SchemaDialect.wireNames() + "; omitted means canonical. Arguments and results are identical in every dialect.";
    }

    /**
     * Closes the SDK server, which closes the transport: later requests are refused by
     * the transport. In-flight calls are not waited for; the host drains its container
     * before calling this and shuts the dispatcher down after.
     */
    public void close() {
        require(server != null, "a started server");
        server.close();
        log.info("{}: closed", COMPONENT);
    }

    private static void require(boolean condition, String what) {
        if (!condition) {
            throw new IllegalStateException(COMPONENT + " requires " + what);
        }
    }

    private McpSchema.CallToolResult serve(ToolProvider provider, McpTransportContext context, McpSchema.CallToolRequest request) {
        try {
            return run(provider, context, request);
        }
        catch (Exception e) {
            return failure(provider, e);
        }
    }

    private McpSchema.CallToolResult run(ToolProvider provider, McpTransportContext context, McpSchema.CallToolRequest request) throws Exception {
        Object consumer = context.get(McpTransportInterposer.CONSUMER_KEY);
        if (!(consumer instanceof McpConsumer resolved)) {
            throw new SystemException(COMPONENT, "tools/call for " + provider.name()
                    + " reached the handler without a resolved consumer; the interposer must front this server", null);
        }
        String principal = resolved.name();
        if (!(context.get(McpTransportInterposer.DIALECT_KEY) instanceof SchemaDialect dialect)) {
            throw new SystemException(COMPONENT, "tools/call for " + provider.name()
                    + " reached the handler without its dialect; the interposer must front this server", null);
        }
        // The request arrives in its path's spelling, because the SDK validated it
        // against that path's published schema on the way here. The dialect's inverse
        // makes it canonical again before anything is parsed from it.
        JsonNode arguments = request.arguments() == null
                ? NucleoJsonSerializer.createObjectNode()
                : dialect.arguments(McpBoundaryJson.TREE_MAPPER.valueToTree(request.arguments()),
                        McpSchemaPublisher.canonicalInputSchema(provider));
        // Arguments reaching here have already passed McpInputGate at the interposer,
        // which judges before the SDK's own validation so the refusal a consumer sees is
        // one this process composed. Anything that still fails to parse is a
        // schema-to-POJO disagreement of ours, and McpErrorText says nothing about it.
        Object input = provider.parseInput(arguments);
        Tool<?, ?> tool = provider.create(Job.workflow(principal, WORKFLOW_PREFIX));
        @SuppressWarnings("unchecked")
        Tool<Object, ?> typed = (Tool<Object, ?>)tool;
        typed.setInput(input);
        if (tool instanceof Thinker<?, ?> thinker) {
            thinker.setInvokedAsTool(true);
        }
        // A registry-aware tool served here is the root of its own flow, so this handler
        // provisions what a dispatching thinker otherwise would. The registry lives for the
        // call: it is what lets the tool register a list artifact for the digest-and-refs
        // answer shape, and the refs it mints are stamped on the artifacts the result carries
        // out. Nothing resolves against it after the response, which is the stateless
        // transport's contract rather than a leak.
        else if (tool instanceof ArtifactRegistryAware registryAware) {
            registryAware.setArtifactRegistry(new ArtifactRegistry());
        }
        // Concurrency is not this handler's concern: the container's pool bounds the threads
        // that reach it, and every submitted tool waits its turn at the dispatcher's admission
        // like any other job. A refusal here would only turn that queue into failures.
        JobDispatcher dispatcher = JobDispatcher.getInstance();
        JobHandle<?> handle = dispatcher.submit(tool);
        Object result;
        try {
            result = handle.get(callTimeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException e) {
            String reason = "MCP call of " + provider.name() + " for " + principal + " timed out after " + callTimeout;
            int cancelled = dispatcher.cancelWorkflow(tool.getWorkflowId(), reason);
            log.warn("{}: {}; cancelled {} jobs of workflow {}", COMPONENT, reason, cancelled, tool.getWorkflowId());
            return errorResult(new JobTimeoutException(callTimeout, e), false);
        }
        // Artifact identity has to be on the wire: a ref is minted at registration, and
        // nothing registered this result, so without this pass artifacts serialize with a
        // null ref and reach the consumer as anonymous data. The registry is throwaway -
        // what survives is the ref stamped on each artifact, which carries its @TypeAlias
        // and is what lets a redouble consumer rebuild the typed object.
        new ArtifactRegistry().indexReachableFrom(result);
        String json = NucleoJsonSerializer.write(result);
        McpSchema.CallToolResult.Builder built = McpSchema.CallToolResult.builder()
                .addTextContent(json)
                .isError(false);
        // Structured content rides exactly the tools that published an output schema:
        // the SDK refuses content without a schema and refuses a schema without content.
        if (McpSchemaPublisher.outputSchemaOf(provider) != null) {
            built.structuredContent(McpJsonDefaults.getMapper(), json);
        }
        return built.build();
    }

    /**
     * Every failure leaving the handler body, mapped once: the framework's LLM-readable
     * text when it has one, a system-exception text otherwise, always logged, never the
     * SDK's raw exception message.
     */
    private McpSchema.CallToolResult failure(ToolProvider provider, Exception e) {
        Throwable cause = e;
        if ((cause instanceof ExecutionException || cause instanceof CompletionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            log.error("{}: call of {} interrupted", COMPONENT, provider.name(), cause);
            return errorResult(new SystemException(COMPONENT, "call of " + provider.name() + " interrupted", cause), false);
        }
        if (cause instanceof LLMReadableException readable) {
            if (readable.isCorrectable()) {
                log.error("{}: {} failed with correctable error: {}", COMPONENT, provider.name(), readable.getLLMMessage());
            }
            else {
                log.error("{}: {} failed: {}", COMPONENT, provider.name(), readable.getLLMMessage(), cause);
            }
            return errorResult(readable, readable.isCorrectable());
        }
        if (cause instanceof LLMReadable readable) {
            log.error("{}: {} failed: {}", COMPONENT, provider.name(), readable.getLLMMessage(), cause);
            return errorResult(readable, false);
        }
        log.error("{}: {} failed", COMPONENT, provider.name(), cause);
        String message = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
        return errorResult(new SystemException(COMPONENT, message, cause), false);
    }

    private static McpSchema.CallToolResult errorResult(LLMReadable readable, boolean correctable) {
        return McpSchema.CallToolResult.builder()
                .addTextContent(McpErrorText.of(readable))
                .isError(true)
                .meta(Map.of(META_CORRECTABLE, correctable))
                .build();
    }
}
