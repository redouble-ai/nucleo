/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.cap;

import ai.redouble.examples.agent.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.observability.*;

import java.util.concurrent.*;

/**
 * A dollar cap on a workflow: this piece of work may spend one cent, and once one cent is
 * committed, no new model work starts. The order agent answers the same question again and
 * again inside one capped workflow until the cap refuses a run.
 *
 * <p>{@link CostLedger} keeps the accounts: subscribed to the dispatcher's events, it
 * prices every model call a finished job made from the catalog, per workflow, per model and
 * per call. Registered as the spend gate, it is also the check every job passes before it
 * starts. The check works on reservations - the input of the job's model calls plus the
 * output its declaration allows, priced - and a job is refused when the workflow's spend,
 * plus what its running jobs have reserved, plus this job's reservation would pass the cap.
 * Counting what is in flight is what keeps a burst of jobs from slipping past the cap
 * before the first is priced. The refused job never sends a request, and nothing already
 * running is stopped.
 *
 * <p>A cap is in one currency and nothing is ever converted: a job whose model is priced in
 * a currency the workflow has no cap for is refused, and so is one whose model has no price
 * at all - a spend nobody can state cannot be admitted.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
public class SpendCap {
    public static void main(String[] args) throws Exception {
        // region cap
        JobDispatcher dispatcher = JobDispatcher.getInstance();
        dispatcher.start();
        CostLedger ledger = new CostLedger();
        dispatcher.subscribe(ledger, JobEvent.class);
        dispatcher.registerSpendGate(ledger);
        try {
            Identifiable workflow = Job.workflow("you", "capped");
            ledger.cap(workflow.getWorkflowId(), new Cost(0.01, "USD"));
            for (int run = 1; ; run++) {
                OrderAgent agent = new OrderAgent(workflow);
                agent.setInput(new OrderQuestion("Where are my orders A-1002 and A-1005?"));
                try {
                    dispatcher.submit(agent).get();
                }
                catch (ExecutionException e) {
                    if (e.getCause() instanceof SpendCapExceededException refused) {
                        System.out.println("run " + run + " refused: " + refused.getMessage());
                        break;
                    }
                    throw e;
                }
                System.out.println("run " + run + " done, spent so far " + ledger.spent(workflow.getWorkflowId(), "USD"));
            }
        }
        finally {
            dispatcher.shutdown(JobDispatcher.DEFAULT_SHUTDOWN_TIMEOUT_MS);
        }
        // endregion
    }
}
