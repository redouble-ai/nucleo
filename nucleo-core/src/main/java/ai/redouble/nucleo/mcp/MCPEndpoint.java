/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp;

import com.fasterxml.jackson.databind.*;
import io.modelcontextprotocol.spec.*;

import java.time.*;

/**
 * Describes how to connect to an MCP server.
 * Contains connection parameters only - no resource management concerns.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-01-10)
 */
public interface MCPEndpoint {
    /**
     * Returns the transport type for this endpoint.
     */
    MCPTransportType getTransportType();

    /**
     * Returns a unique identifier derived from the endpoint configuration.
     * Used by {@link MCPClientPool} for client caching and circuit-breaking, and by
     * {@link STDIOEndpointPool} for STDIO-only rate limiting.
     * For STDIO: command + args (e.g., "npx -y @brave/brave-search-mcp-server")
     * For HTTP: the URL
     */
    String getEndpointId();

    /**
     * Creates the appropriate transport for this endpoint.
     *
     * @param objectMapper the shared ObjectMapper for JSON serialization
     * @return configured transport ready for connection
     */
    McpClientTransport createTransport(ObjectMapper objectMapper);

    /**
     * Per-call timeout for {@code tools/call} responses from this endpoint, passed to the
     * SDK's {@code McpClient.sync(...).requestTimeout(...)} builder. The SDK default of 20
     * seconds is appropriate for stateless servers (Brave, Exa), but stateful or
     * browser-driving servers may need longer to respond on heavy pages. Override per
     * endpoint where the wait is legitimate; do not raise the default globally or
     * stateless calls lose their fast-fail property.
     */
    default Duration getRequestTimeout() {
        return Duration.ofSeconds(20);
    }
}
