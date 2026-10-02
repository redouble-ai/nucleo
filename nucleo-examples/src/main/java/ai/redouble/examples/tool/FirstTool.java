/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.tool;

import ai.redouble.nucleo.harness.*;

/**
 * The order-status tool submitted by plain Java code, the way any job is: the tool is the
 * same object whether code or a model calls it, and the next example hands it to a model.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
public class FirstTool {
    public static void main(String[] args) throws Exception {
        // region submit
        JobDispatcher dispatcher = JobDispatcher.getInstance();
        dispatcher.start();
        try {
            OrderStatusTool tool = new OrderStatusTool(Job.workflow("you", "first-tool"));
            tool.setInput(new OrderNumber("A-1002"));
            OrderStatus order = dispatcher.submit(tool).get();
            System.out.println(order);
        }
        finally {
            dispatcher.shutdown(JobDispatcher.DEFAULT_SHUTDOWN_TIMEOUT_MS);
        }
        // endregion
    }
}
