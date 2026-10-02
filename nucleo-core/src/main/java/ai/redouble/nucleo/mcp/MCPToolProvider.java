/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp;

import ai.redouble.nucleo.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.registry.*;
import com.fasterxml.jackson.databind.*;

/**
 * {@link ToolProvider} for an MCP-served tool. Carries the namespaced LLM-facing identity
 * ({@link MCPHandle#namespacedToolName(String)}), the normalized schema ({@link MCPSchemaNormalizer}),
 * and a factory that wires a {@link GenericMCPToolAdapter} on demand.
 *
 * <p>The provider does <em>not</em> capture an {@link MCPClient}. {@link #create(Identifiable)}
 * pulls a live client from {@link MCPClientPool#getConnectedClient(MCPEndpoint)} so a closed
 * or evicted client cannot poison the provider; the connector picks up a fresh client on
 * the next call.
 *
 * <p>No client-side input validation runs before dispatching the call.
 * {@link #parseInput(JsonNode)} wraps the raw JSON node into an {@link MCPToolInput}
 * envelope; the server is the schema authority and rejections surface as
 * {@code InvalidInputException} via {@link GenericMCPClient#callTool}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-05-08)
 */
public final class MCPToolProvider implements ToolProvider {
    private final MCPHandle handle;
    private final MCPEndpoint endpoint;
    private final MCPToolDescriptor descriptor;
    private final String namespacedName;
    private final String schemaJson;
    private final String description;

    public MCPToolProvider(MCPHandle handle, MCPEndpoint endpoint, MCPToolDescriptor descriptor) {
        this.handle = handle;
        this.endpoint = endpoint;
        this.descriptor = descriptor;
        this.namespacedName = handle.namespacedToolName(descriptor.getName());
        JsonNode normalized = MCPSchemaNormalizer.normalize(descriptor.getInputSchema());
        this.schemaJson = NucleoJsonSerializer.write(normalized);
        this.description = truncate(descriptor.getDescription(), Settings.get(McpSettings.class).descMaxChars);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }

    @Override
    public String name() {
        return namespacedName;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public String schemaJson() {
        return schemaJson;
    }

    @Override
    public ToolWeight weight() {
        return DefaultToolWeights.API_CALL_DEFAULT;
    }

    @Override
    public String displayName() {
        return namespacedName;
    }

    @Override
    public String actionVerb() {
        return "";
    }

    @Override
    public Class<?> inputType() {
        return MCPToolInput.class;
    }

    @Override
    public Class<? extends Tool> toolClass() {
        return GenericMCPToolAdapter.class;
    }

    @Override
    public Object parseInput(JsonNode raw) throws CorrectableLLMException {
        MCPToolInput in = new MCPToolInput();
        in.setArguments(raw);
        return in;
    }

    @Override
    public Tool<?, ?> create(Identifiable parent) throws LLMReadableCheckedException {
        MCPClient client = MCPClientPool.getConnectedClient(endpoint);
        return new GenericMCPToolAdapter(parent, client, descriptor, handle);
    }

    public MCPHandle handle() {
        return handle;
    }

    public MCPEndpoint endpoint() {
        return endpoint;
    }

    public MCPToolDescriptor descriptor() {
        return descriptor;
    }

    public JsonNode originalSchema() {
        return descriptor.getInputSchema();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MCPToolProvider other
                && other.handle.equals(this.handle)
                && other.endpoint.getEndpointId().equals(this.endpoint.getEndpointId())
                && other.descriptor.getName().equals(this.descriptor.getName());
    }

    @Override
    public int hashCode() {
        int result = handle.hashCode();
        result = result * 31 + endpoint.getEndpointId().hashCode();
        result = result * 31 + descriptor.getName().hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "MCPToolProvider[" + namespacedName + "]";
    }
}
