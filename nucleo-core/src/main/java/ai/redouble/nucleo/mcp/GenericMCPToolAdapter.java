/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.*;
import com.fasterxml.jackson.databind.node.*;

/**
 * Adapts an MCP-served tool to the Nucleo tool framework. Wraps an
 * {@link MCPClient} and a specific {@link MCPToolDescriptor}, exposing the standard
 * {@link ai.redouble.nucleo.tools.Tool} contract so MCP tools participate in thinker tool
 * loops alongside native ones.
 *
 * <p>The adapter takes a connector {@link MCPHandle} so observability metadata
 * ({@code obs.mcp.handle}, {@code obs.mcp.tool}) and the namespaced LLM-facing
 * identity stay consistent with {@link MCPToolProvider#name()}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-01-10)
 */
public class GenericMCPToolAdapter extends AbstractTool<MCPToolInput, MCPToolResult> {
    private final MCPClient client;
    private final MCPToolDescriptor descriptor;
    private final MCPHandle handle;

    /**
     * Creates an adapter for a specific MCP tool.
     *
     * @param parent     the parent for lineage tracking
     * @param client     the MCP client to use for tool calls
     * @param descriptor the descriptor for the specific tool to wrap
     * @param handle     the connector handle that owns this tool (required so
     *                   observability metadata and namespacing line up with the
     *                   provider)
     */
    public GenericMCPToolAdapter(Identifiable parent, MCPClient client, MCPToolDescriptor descriptor, MCPHandle handle) {
        super(parent, handle.namespacedToolName(descriptor.getName()));
        this.client = client;
        this.descriptor = descriptor;
        this.handle = handle;
    }

    @Override
    public JobRequirements getRequirements() {
        JobRequirements req = new JobRequirements();
        // The transport decides the resources a call holds: for STDIO a slot on the endpoint's
        // own limiter and a slot on the pool-wide subprocess cap, declared separately so
        // admission reserves both for the head of its queue; for HTTP a connection from the
        // shared HTTP pool.
        switch (client.getEndpoint().getTransportType()) {
            case STDIO -> {
                req.requireRateLimiter(client.getRateLimiter(), null);
                req.requireRateLimiter(STDIOEndpointPool.globalGate(), null);
            }
            case HTTP -> req.setRequiresHttpConnection(true);
        }
        return req;
    }

    @Override
    public MCPToolResult execute(JobResources resources, JobContext<MCPToolResult> ctx) throws LLMReadableCheckedException {
        MCPToolResult result = client.callTool(descriptor.getName(), input.getArguments());
        // A server that is itself a redouble process serves artifacts carrying their own
        // refs, and a ref names its type. Rebuild the typed artifact when the payload is
        // one and its alias resolves here; everything else - every third-party server, and
        // any type this process does not know at any level of the alias hierarchy - takes
        // the generic path below.
        Artifact rehydrated = MCPArtifactRehydrator.rehydrate(result.getTextContent());
        if (rehydrated != null) {
            result.setResultArtifact(rehydrated);
            return result;
        }
        // Walk the structured result into an MCPArtifact: nested sub-objects past the
        // promotion threshold become nested artifacts; long string leaves get cached
        // summaries keyed by JSON pointer. The framework's serializer reads from the
        // cache via @LLMSummarizable(preSummarized=true) on MCPArtifact.data.
        // Content is @JsonIgnore'd on MCPToolResult, so we feed the walker a wrapper
        // tree built explicitly from getContent() rather than valueToTree(result).
        ObjectNode wrapper = NucleoJsonSerializer.createObjectNode();
        wrapper.set("content", NucleoJsonSerializer.valueToTree(result.getContent()));
        MCPArtifact artifact = MCPResultWalker.walk(wrapper, handle, descriptor.getName());
        result.setResultArtifact(artifact);
        return result;
    }

    public MCPToolDescriptor getDescriptor() {
        return descriptor;
    }

    public String getToolName() {
        return descriptor.getName();
    }

    public String getToolDescription() {
        return descriptor.getDescription();
    }

    public MCPClient getClient() {
        return client;
    }

    public MCPHandle getHandle() {
        return handle;
    }
}
