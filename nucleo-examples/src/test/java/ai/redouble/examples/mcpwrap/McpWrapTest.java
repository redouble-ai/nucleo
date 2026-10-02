/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.mcpwrap;

import ai.redouble.examples.tool.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.mcp.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the mcpwrap page promises of the typed wrapper: a test hands {@link OrdersServer} a
 * fake client and {@link RemoteOrderStatusTool} runs without a network, sending the tool's
 * own name and the order number in the server's snake_case schema and parsing the text
 * result into the typed {@code OrderStatus}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-28)
 */
class McpWrapTest {
    @BeforeAll
    static void start() {
        JobDispatcher.getInstance().start();
    }

    @AfterAll
    static void stop() {
        OrdersServer.close();
        JobDispatcher.getInstance().shutdown(JobDispatcher.DEFAULT_SHUTDOWN_TIMEOUT_MS);
    }

    @Test
    void theWrapperRunsAgainstAFakeClientWithoutANetwork() throws Exception {
        AtomicReference<String> calledTool = new AtomicReference<>();
        AtomicReference<JsonNode> calledWith = new AtomicReference<>();
        OrdersServer.use(new MCPClient() {
            @Override
            public RateLimiter<Void> getRateLimiter() {
                return null;
            }

            @Override
            public List<MCPToolDescriptor> listTools() {
                return List.of();
            }

            @Override
            public MCPToolResult callTool(String toolName, JsonNode arguments) {
                calledTool.set(toolName);
                calledWith.set(arguments);
                MCPContent content = new MCPContent();
                content.setType("text");
                content.setText("{\"order_number\":\"A-1002\",\"customer_id\":\"C-100\",\"status\":\"DELAYED\","
                        + "\"promised_for\":\"2026-10-02\",\"note\":\"carrier reports weather delay\"}");
                MCPToolResult result = new MCPToolResult();
                result.setContent(List.of(content));
                return result;
            }

            @Override
            public MCPServerInfo getServerInfo() {
                return null;
            }

            @Override
            public boolean isConnected() {
                return true;
            }

            @Override
            public MCPEndpoint getEndpoint() {
                return null;
            }

            @Override
            public void close() {
            }
        });
        RemoteOrderStatusTool tool = new RemoteOrderStatusTool(Job.workflow("test", "mcp-wrap"));
        tool.setInput(new OrderNumber("A-1002"));
        OrderStatus order = JobDispatcher.getInstance().submit(tool).get();
        assertEquals("order_status", calledTool.get(), "the wrapper calls the server's tool by its own name");
        assertEquals("A-1002", calledWith.get().get("order_number").asText(),
                "the argument travels in the server's snake_case schema");
        assertEquals("DELAYED", order.getStatus(), "the text result is parsed into the typed OrderStatus");
        assertEquals("C-100", order.getCustomerId());
    }
}
