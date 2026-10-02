/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.benchmark;

import ai.redouble.examples.agent.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.tools.benchmark.*;

/**
 * Is the grade right for this job, and which model at that grade does it best, for what
 * cost and how fast? The benchmark answers with evidence: it runs the job once the way the
 * deployment would (the reference), then the same job with the same input on every model of
 * the grade the deployment can call, and a judge - the strongest model the deployment
 * serves - compares each run with the reference in full, not told which model ran either
 * side. A candidate that skipped a lookup loses points even when its reply reads well.
 *
 * <p>{@link Benchmark} is a doer over a factory, because every run needs a fresh agent, and
 * any job whose model is chosen by grade can be raced this way, agents and one-call tools
 * alike. It returns what the reference answered, so a workflow that wraps a step in a
 * benchmark receives exactly what the step would have returned; the measurements come
 * beside it, one row per run with cost, speed and the judge's verdict, from
 * {@code report()}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
public class RaceTheGrade {
    public static void main(String[] args) throws Exception {
        // region race
        JobDispatcher dispatcher = JobDispatcher.getInstance();
        dispatcher.start();
        try {
            Identifiable workflow = Job.workflow("you", "race");
            Benchmark<OrderQuestion, OrderAnswer> race = new Benchmark<>(workflow, () -> new OrderAgent(workflow), 1);
            race.setInput(new OrderQuestion("Where are my orders A-1002 and A-1005?"));
            OrderAnswer reference = dispatcher.submit(race).get();
            System.out.println(reference.getReply());
            System.out.println(race.report().table());
        }
        finally {
            dispatcher.shutdown(JobDispatcher.DEFAULT_SHUTDOWN_TIMEOUT_MS);
        }
        // endregion
    }
}
