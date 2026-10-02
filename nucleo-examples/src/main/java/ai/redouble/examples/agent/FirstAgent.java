/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.agent;

import ai.redouble.nucleo.harness.*;

/**
 * The order agent asked one question: submitted like the tool was, answered as an
 * {@link OrderAnswer}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
public class FirstAgent {
    public static void main(String[] args) throws Exception {
        // region ask
        JobDispatcher dispatcher = JobDispatcher.getInstance();
        dispatcher.start();
        try {
            OrderAgent agent = new OrderAgent(Job.workflow("you", "first-agent"));
            agent.setInput(new OrderQuestion("Where are my orders A-1002 and A-1005?"));
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
