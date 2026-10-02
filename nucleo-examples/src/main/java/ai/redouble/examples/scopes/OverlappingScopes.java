/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.scopes;

import ai.redouble.nucleo.harness.*;

/**
 * The shift run for C-100: five notes attempted under the overlapping bindings, each
 * outcome printed. No model is involved; every refusal is the runtime's.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-28)
 */
public class OverlappingScopes {
    public static void main(String[] args) throws Exception {
        // region run
        JobDispatcher dispatcher = JobDispatcher.getInstance();
        dispatcher.start();
        try {
            EmailShiftDoer shift = new EmailShiftDoer(Job.workflow("you", "shift"), "C-100");
            shift.setInput("work the case");
            for (String outcome : dispatcher.submit(shift).get()) {
                System.out.println(outcome);
            }
        }
        finally {
            dispatcher.shutdown(JobDispatcher.DEFAULT_SHUTDOWN_TIMEOUT_MS);
        }
        // endregion
    }
}
