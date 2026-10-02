/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.mcpclient;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.mcp.*;
import com.fasterxml.jackson.databind.node.*;

/**
 * Connecting to an MCP server the generic way, with no server-specific code: name the
 * endpoint, attach, and every tool the server grants is ready to hand to an agent
 * ({@code thinker.addTool(provider)}, shown to the model as {@code orders_order_status}:
 * handle, underscore, the tool's own name) or to call from code, as here. Start
 * {@code ai.redouble.examples.mcpserver.OrdersOverMcp} first; this main talks to it on
 * localhost.
 *
 * <p>Notice what travels: the arguments are an untyped JSON object spelled to match a
 * schema this code never sees, and the result is text to pick apart. For browsing and
 * one-off calls that generality is exactly right; for a tool your workflows depend on,
 * wrap it as {@code ai.redouble.examples.mcpwrap} does.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-28)
 */
public class ListAndCall {
    public static void main(String[] args) throws Exception {
        // region attach
        HTTPMCPEndpoint endpoint = new HTTPMCPEndpoint();
        endpoint.setUrl("http://localhost:8901/mcp");
        endpoint.setFlavor(HTTPMCPEndpoint.Flavor.STREAMABLE_HTTP);

        JobDispatcher dispatcher = JobDispatcher.getInstance();
        dispatcher.start();
        MCPConnector connector = MCPConnectorRegistry.attach(new MCPHandle("orders"), endpoint);
        try {
            for (MCPToolProvider provider : connector.providers()) {
                System.out.println(provider.name() + ": " + provider.description());
            }
            MCPToolProvider orderStatus = connector.providers().get(0);
            ObjectNode arguments = NucleoJsonSerializer.createObjectNode();
            arguments.put("order_number", "A-1002");
            MCPToolInput input = new MCPToolInput();
            input.setArguments(arguments);
            GenericMCPToolAdapter call = (GenericMCPToolAdapter) orderStatus.create(Job.workflow("you", "mcp-call"));
            call.setInput(input);
            MCPToolResult result = dispatcher.submit(call).get();
            System.out.println(result.getTextContent());
        }
        finally {
            MCPConnectorRegistry.detach(connector);
            dispatcher.shutdown(JobDispatcher.DEFAULT_SHUTDOWN_TIMEOUT_MS);
        }
        // endregion
    }
}
