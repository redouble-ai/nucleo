/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp;

import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.*;
import io.modelcontextprotocol.client.*;
import io.modelcontextprotocol.client.transport.*;
import io.modelcontextprotocol.spec.*;
import org.slf4j.*;

import java.util.*;

/**
 * Generic MCP client implementation backed by the official MCP Java SDK (version managed
 * by the reactor's BOM, {@code mcp.sdk.version}).
 *
 * <p>HTTP-transport authorization failures surface as
 * {@link io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException}
 * from the SDK. They are mapped to {@link UnauthorizedException} so the framework's
 * LLM-readable hierarchy treats them uniformly with other 401/403 sources.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-01-10)
 */
public class GenericMCPClient implements MCPClient {
    private static final Logger log = LoggerFactory.getLogger(GenericMCPClient.class);
    private static volatile McpSchema.Implementation clientIdentity =
            McpSchema.Implementation.builder("nucleo", libraryVersion()).build();
    private final MCPEndpoint endpoint;
    private final RateLimiter<Void> rateLimiter;
    private McpSyncClient sdkClient;
    private MCPServerInfo serverInfo;
    private volatile boolean connected;

    private GenericMCPClient(MCPEndpoint endpoint, RateLimiter<Void> rateLimiter) {
        this.endpoint = endpoint;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Names what this process introduces itself as to every MCP server it connects to -
     * the {@code clientInfo} of the initialize handshake, which servers log and meter by.
     * The identity belongs to the deployment's application, so a host sets its own name
     * and version once at startup; until it does, the runtime introduces itself as
     * {@code nucleo} at the library's version.
     */
    public static void identifyAs(String name, String version) {
        if (name == null || name.isBlank() || version == null || version.isBlank()) {
            throw new IllegalArgumentException("An MCP client identity needs a name and a version");
        }
        clientIdentity = McpSchema.Implementation.builder(name, version).build();
    }

    /** The identity the next handshake presents. Package-private for its test. */
    static McpSchema.Implementation clientIdentity() {
        return clientIdentity;
    }

    /** The library's own version from the jar manifest, or {@code dev} outside a packaged build. */
    private static String libraryVersion() {
        String version = GenericMCPClient.class.getPackage().getImplementationVersion();
        return version != null ? version : "dev";
    }

    /**
     * Creates and connects a client for the given endpoint.
     *
     * @param endpoint the MCP endpoint configuration
     * @return connected MCP client
     * @throws ExternalServiceException if connection fails for transport reasons
     * @throws UnauthorizedException if the server rejects credentials at handshake
     */
    public static GenericMCPClient connect(MCPEndpoint endpoint) throws LLMReadableCheckedException {
        RateLimiter<Void> rateLimiter = getRateLimiterForEndpoint(endpoint);
        GenericMCPClient client = new GenericMCPClient(endpoint, rateLimiter);
        client.initialize();
        return client;
    }

    private static RateLimiter<Void> getRateLimiterForEndpoint(MCPEndpoint endpoint) {
        if (endpoint.getTransportType() == MCPTransportType.STDIO) {
            return STDIOEndpointPool.getLimiter((STDIOMCPEndpoint) endpoint);
        }
        // HTTP endpoints use shared connection pool - no additional rate limiting
        return null;
    }

    private void initialize() throws LLMReadableCheckedException {
        try {
            McpClientTransport transport = endpoint.createTransport(new ObjectMapper());
            this.sdkClient = McpClient.sync(transport)
                    .clientInfo(clientIdentity)
                    .requestTimeout(endpoint.getRequestTimeout())
                    .build();
            McpSchema.InitializeResult initResult = sdkClient.initialize();
            this.serverInfo = convertServerInfo(initResult);
            this.connected = true;
            log.info("Connected to MCP server: {} v{}", serverInfo.getName(), serverInfo.getVersion());
        }
        catch (McpHttpClientTransportAuthorizationException e) {
            throw new UnauthorizedException("MCP:" + endpoint.getEndpointId(), e.getMessage());
        }
        catch (Exception e) {
            throw typedCauseOr(e, "Failed to connect: " + e.getMessage());
        }
    }

    private MCPToolDescriptor toDescriptor(McpSchema.Tool tool) {
        MCPToolDescriptor d = new MCPToolDescriptor();
        d.setName(tool.name());
        d.setDescription(tool.description());
        d.setInputSchema(NucleoJsonSerializer.valueToTree(tool.inputSchema()));
        return d;
    }

    private MCPServerInfo convertServerInfo(McpSchema.InitializeResult initResult) {
        MCPServerInfo info = new MCPServerInfo();
        info.setName(initResult.serverInfo().name());
        info.setVersion(initResult.serverInfo().version());
        info.setProtocolVersion(initResult.protocolVersion());
        List<String> capabilities = new ArrayList<>();
        McpSchema.ServerCapabilities caps = initResult.capabilities();
        if (caps.tools() != null) capabilities.add("tools");
        if (caps.resources() != null) capabilities.add("resources");
        if (caps.prompts() != null) capabilities.add("prompts");
        info.setCapabilities(capabilities);
        return info;
    }

    @Override
    public RateLimiter<Void> getRateLimiter() {
        return rateLimiter;
    }

    @Override
    public List<MCPToolDescriptor> listTools() throws LLMReadableCheckedException {
        ensureConnected();
        try {
            McpSchema.ListToolsResult result = sdkClient.listTools();
            List<MCPToolDescriptor> out = new ArrayList<>(result.tools().size());
            for (McpSchema.Tool tool : result.tools()) {
                out.add(toDescriptor(tool));
            }
            return out;
        }
        catch (McpHttpClientTransportAuthorizationException e) {
            throw new UnauthorizedException("MCP:" + endpoint.getEndpointId(), e.getMessage());
        }
        catch (Exception e) {
            throw typedCauseOr(e, "Failed to list tools: " + e.getMessage());
        }
    }

    /**
     * The SDK wraps what its transport threw; what the transport threw may be its own
     * authorization exception or, from the endpoint's token flow, one of ours. Either is
     * rethrown as the typed exception it is; anything else is an external failure.
     */
    private LLMReadableCheckedException typedCauseOr(Exception e, String otherwise) {
        for (Throwable root = e.getCause(); root != null; root = root.getCause()) {
            if (root instanceof McpHttpClientTransportAuthorizationException auth) {
                return new UnauthorizedException("MCP:" + endpoint.getEndpointId(), auth.getMessage());
            }
            if (root instanceof LLMReadableCheckedException typed) {
                return typed;
            }
        }
        return new ExternalServiceException("MCP:" + endpoint.getEndpointId(), otherwise, e);
    }

    @Override
    public MCPToolResult callTool(String toolName, JsonNode arguments) throws LLMReadableCheckedException {
        ensureConnected();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> argsMap = NucleoJsonSerializer.convert(arguments, Map.class);
            McpSchema.CallToolResult result = sdkClient.callTool(McpSchema.CallToolRequest.builder(toolName).arguments(argsMap).build());
            MCPToolResult mcpResult = new MCPToolResult();
            List<MCPContent> contents = new ArrayList<>();
            for (McpSchema.Content content : result.content()) {
                MCPContent mcpContent = new MCPContent();
                if (content instanceof McpSchema.TextContent textContent) {
                    mcpContent.setType("text");
                    mcpContent.setText(textContent.text());
                }
                else if (content instanceof McpSchema.ImageContent imageContent) {
                    mcpContent.setType("image");
                    mcpContent.setMimeType(imageContent.mimeType());
                    mcpContent.setData(imageContent.data());
                }
                else if (content instanceof McpSchema.ResourceLink resourceLink) {
                    mcpContent.setType("resource");
                    mcpContent.setText(resourceLink.uri());
                }
                contents.add(mcpContent);
            }
            mcpResult.setContent(contents);
            if (Boolean.TRUE.equals(result.isError())) {
                throw new InvalidInputException(toolName, endpoint.getEndpointId(), "MCP tool '" + toolName + "' returned error: " + mcpResult.getTextContent());
            }
            return mcpResult;
        }
        catch (InvalidInputException e) {
            throw e;
        }
        catch (McpHttpClientTransportAuthorizationException e) {
            throw new UnauthorizedException("MCP:" + endpoint.getEndpointId(), e.getMessage());
        }
        catch (Exception e) {
            throw typedCauseOr(e, "Failed to call tool '" + toolName + "': " + e.getMessage());
        }
    }

    @Override
    public MCPServerInfo getServerInfo() {
        return serverInfo;
    }

    @Override
    public boolean isConnected() {
        return connected && sdkClient != null;
    }

    @Override
    public MCPEndpoint getEndpoint() {
        return endpoint;
    }

    @Override
    public void close() {
        connected = false;
        if (sdkClient != null) {
            try {
                sdkClient.close();
            }
            // Swallowing in close() is intentional: propagating would mask the original exception that triggered shutdown
            catch (Exception e) {
                log.warn("Error closing MCP client: {}", e.getMessage());
            }
            sdkClient = null;
        }
    }

    private void ensureConnected() throws ExternalServiceException {
        if (!isConnected()) {
            throw new ExternalServiceException("MCP:" + endpoint.getEndpointId(), "Client is not connected");
        }
    }
}
