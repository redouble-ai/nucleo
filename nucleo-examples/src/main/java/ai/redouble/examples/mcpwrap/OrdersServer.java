/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.mcpwrap;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.mcp.*;

/**
 * The application's one connection to the orders MCP server: connected at startup, shared
 * by every tool that wraps one of the server's tools, closed at shutdown. Holding the
 * client here is what lets a wrapping tool keep the {@code (Identifiable parent)}
 * constructor every tool has, so an agent can create it like any other.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-28)
 */
public final class OrdersServer {
    private static volatile MCPClient client;

    private OrdersServer() {
    }

    public static void connect(String url) throws LLMReadableCheckedException {
        HTTPMCPEndpoint endpoint = new HTTPMCPEndpoint();
        endpoint.setUrl(url);
        endpoint.setFlavor(HTTPMCPEndpoint.Flavor.STREAMABLE_HTTP);
        client = GenericMCPClient.connect(endpoint);
    }

    /** The seam a test uses: hand a fake client and the wrapping tools run without a network. */
    static void use(MCPClient fake) {
        client = fake;
    }

    public static MCPClient client() {
        MCPClient connected = client;
        if (connected == null) {
            throw new IllegalStateException("OrdersServer.connect(url) has not run");
        }
        return connected;
    }

    public static void close() {
        MCPClient connected = client;
        client = null;
        if (connected != null) {
            connected.close();
        }
    }
}
