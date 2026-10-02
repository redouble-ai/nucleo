/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.doer;

import ai.redouble.examples.tool.*;
import ai.redouble.nucleo.harness.*;

/**
 * The customer-report doer run for one customer: every order it looked up, then the model's
 * answer over them.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
public class FirstDoer {
    public static void main(String[] args) throws Exception {
        // region run
        JobDispatcher dispatcher = JobDispatcher.getInstance();
        dispatcher.start();
        try {
            CustomerReportDoer doer = new CustomerReportDoer(Job.workflow("you", "first-doer"));
            doer.setInput(new CustomerQuestion("C-100",
                    "Which of these orders should the customer be told about first, and why?"));
            CustomerReport report = dispatcher.submit(doer).get();
            for (OrderStatus order : report.getOrders()) {
                System.out.println(order);
            }
            System.out.println(report.getAnswer());
        }
        finally {
            dispatcher.shutdown(JobDispatcher.DEFAULT_SHUTDOWN_TIMEOUT_MS);
        }
        // endregion
    }
}
