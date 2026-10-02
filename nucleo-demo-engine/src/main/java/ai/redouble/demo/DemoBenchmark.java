/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.tools.benchmark.*;

/**
 * The demo's benchmark of {@link DemoAgent}: the reference answers on the strongest model the
 * deployment serves - resolved through {@link Grade#CEILING}, the same way the judge is - while
 * every raced copy is re-pinned to its candidate, and the judges wait behind the same person
 * the raced agents do. A named class, so its job ids and its log lines
 * ({@code ai.redouble.demo.DemoBenchmark.progress} for the runs-finished count) name it.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-22)
 */
public final class DemoBenchmark extends Benchmark<DemoQuery, DemoAnswer> {

    /**
     * @param workflow the workflow the benchmark and every agent it builds belong to
     * @param runs     how many times each candidate answers
     */
    public DemoBenchmark(Identifiable workflow, int runs) {
        super(workflow, () -> {
            DemoAgent agent = new DemoAgent(workflow);
            agent.setGrade(Grade.CEILING);
            return agent;
        }, runs);
    }

    @Override
    protected JudgeTool newJudge() {
        JudgeTool judge = super.newJudge();
        judge.setUpstreamRetries(DemoPolicy.UPSTREAM_RETRIES);
        return judge;
    }
}
