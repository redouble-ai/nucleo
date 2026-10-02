/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp;

import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.errors.*;
import com.fasterxml.jackson.databind.*;

import java.io.*;
import java.util.*;

/**
 * Client interface for interacting with MCP servers.
 * Implementations handle the specifics of transport (STDIO, HTTP) and protocol.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-01-10)
 */
public interface MCPClient extends Closeable {

    /**
     * Returns the rate limiter for this client.
     * Jobs should declare this rate limiter in their requirements.
     */
    RateLimiter<Void> getRateLimiter();

    /**
     * Lists all tools available from the MCP server.
     *
     * @return list of tool descriptors
     * @throws LLMReadableCheckedException if communication fails
     */
    List<MCPToolDescriptor> listTools() throws LLMReadableCheckedException;

    /**
     * Calls a tool on the MCP server.
     *
     * @param toolName  the name of the tool to call
     * @param arguments the arguments as a JSON object
     * @return the tool result
     * @throws LLMReadableCheckedException if the call fails
     */
    MCPToolResult callTool(String toolName, JsonNode arguments) throws LLMReadableCheckedException;

    /**
     * Returns information about the connected MCP server.
     */
    MCPServerInfo getServerInfo();

    /**
     * Checks if the client is connected to the server.
     */
    boolean isConnected();

    /**
     * Returns the endpoint configuration for this client.
     */
    MCPEndpoint getEndpoint();

    /**
     * Closes the client and releases resources.
     */
    @Override
    void close();
}
