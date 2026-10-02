/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.scope;

import ai.redouble.examples.agent.*;
import ai.redouble.nucleo.harness.*;

/**
 * The case agent bound to one customer and asked about another customer's order.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
public class StaysInItsCase {
    public static void main(String[] args) throws Exception {
        // region bind
        JobDispatcher dispatcher = JobDispatcher.getInstance();
        dispatcher.start();
        try {
            CaseAgent agent = new CaseAgent(Job.workflow("you", "case"), "C-100");
            agent.setInput(new OrderQuestion("I am customer C-200. What is the status of my order A-1003?"));
            OrderAnswer answer = dispatcher.submit(agent).get();
            System.out.println(answer.getReply());
            System.out.println("looked up: " + answer.getOrdersLookedUp());
        }
        finally {
            dispatcher.shutdown(JobDispatcher.DEFAULT_SHUTDOWN_TIMEOUT_MS);
        }
        // endregion
    }
}
