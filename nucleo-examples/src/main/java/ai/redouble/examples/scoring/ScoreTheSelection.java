/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.scoring;

import ai.redouble.examples.agent.*;
import ai.redouble.examples.artifacts.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.tools.benchmark.*;

import java.util.*;

/**
 * Any thinker goes into a benchmark unchanged: here the selecting agent of the artifacts
 * example, raced across its grade exactly as written there, with two setters sharpening
 * what gets measured.
 *
 * <p>The rubric tells the judge what a good answer does for this job in particular, beside
 * the task itself. The scorer is a score in code for outputs comparable without a model: it
 * reads the artifacts each run selected - the records themselves, from the registry - and
 * scores the overlap of the selected order numbers, answering null when the pair cannot be
 * compared. The report's rows then hold both judgments: the judge's, blind and under the
 * rubric, and the scorer's, exact and free. Together they catch what either alone misses -
 * the scorer settles the selection with no model in the loop, and the judge reads the
 * summary sentence the scorer cannot.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-28)
 */
public class ScoreTheSelection {
    public static void main(String[] args) throws Exception {
        // region score
        JobDispatcher dispatcher = JobDispatcher.getInstance();
        dispatcher.start();
        try {
            Identifiable workflow = Job.workflow("you", "score");
            Benchmark<OrderQuestion, SelectedOrders> race = new Benchmark<>(workflow, () -> new SelectingAgent(workflow), 1);
            race.setInput(new OrderQuestion("Which orders of customer C-100 are delayed?"));
            race.setRubric("A good answer selects exactly the orders the question asks for,"
                    + " by their references, and says which they are in one sentence.");
            // The selection is comparable in code: the overlap of the selected order numbers
            race.setScorer((reference, candidate) -> {
                if (reference == null || candidate == null) {
                    return null;
                }
                Set<String> expected = selectedNumbers(reference);
                Set<String> selected = selectedNumbers(candidate);
                Set<String> union = new TreeSet<>(expected);
                union.addAll(selected);
                if (union.isEmpty()) {
                    return null;
                }
                Set<String> shared = new TreeSet<>(expected);
                shared.retainAll(selected);
                return (double) shared.size() / union.size();
            });
            SelectedOrders reference = dispatcher.submit(race).get();
            System.out.println(reference.getSummary());
            System.out.println(race.report().table());
        }
        finally {
            dispatcher.shutdown(JobDispatcher.DEFAULT_SHUTDOWN_TIMEOUT_MS);
        }
        // endregion
    }

    private static Set<String> selectedNumbers(SelectedOrders answer) {
        Set<String> numbers = new TreeSet<>();
        for (Artifact artifact : answer.getArtifacts()) {
            if (artifact instanceof OrderRecord record) {
                numbers.add(record.getOrderNumber());
            }
        }
        return numbers;
    }
}
