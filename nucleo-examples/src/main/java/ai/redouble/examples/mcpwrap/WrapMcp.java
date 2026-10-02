/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.mcpwrap;

import ai.redouble.examples.tool.*;
import ai.redouble.nucleo.harness.*;

/**
 * The wrapped remote tool called like any local one: a typed input in, a typed
 * {@link OrderStatus} out. Start {@code ai.redouble.examples.mcpserver.OrdersOverMcp}
 * first.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-28)
 */
public class WrapMcp {
    public static void main(String[] args) throws Exception {
        // region call
        JobDispatcher dispatcher = JobDispatcher.getInstance();
        dispatcher.start();
        OrdersServer.connect("http://localhost:8901/mcp");
        try {
            RemoteOrderStatusTool tool = new RemoteOrderStatusTool(Job.workflow("you", "mcp-wrap"));
            tool.setInput(new OrderNumber("A-1002"));
            OrderStatus order = dispatcher.submit(tool).get();
            System.out.println(order);
        }
        finally {
            OrdersServer.close();
            dispatcher.shutdown(JobDispatcher.DEFAULT_SHUTDOWN_TIMEOUT_MS);
        }
        // endregion
    }
}
