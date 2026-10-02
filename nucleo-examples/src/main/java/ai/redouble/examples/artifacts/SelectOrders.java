/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.artifacts;

import ai.redouble.examples.agent.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.artifacts.*;

/**
 * The selecting agent asked about one customer's orders: the summary is the model's, the
 * records are the tool's.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
public class SelectOrders {
    public static void main(String[] args) throws Exception {
        // region select
        JobDispatcher dispatcher = JobDispatcher.getInstance();
        dispatcher.start();
        try {
            SelectingAgent agent = new SelectingAgent(Job.workflow("you", "select-orders"));
            agent.setInput(new OrderQuestion("Which orders of customer C-100 are delayed?"));
            SelectedOrders answer = dispatcher.submit(agent).get();
            System.out.println(answer.getSummary());
            for (Artifact artifact : answer.getArtifacts()) {
                // The object the tool built, from the registry: no field of it passed through the model
                OrderRecord record = (OrderRecord) artifact;
                System.out.println(record.getArtifactRef() + " " + record);
            }
        }
        finally {
            dispatcher.shutdown(JobDispatcher.DEFAULT_SHUTDOWN_TIMEOUT_MS);
        }
        // endregion
    }
}
