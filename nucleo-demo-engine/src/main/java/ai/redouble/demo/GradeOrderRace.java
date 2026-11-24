/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

import ai.redouble.nucleo.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.tools.benchmark.*;
import org.slf4j.*;

import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * The race a first discovery orders the grades by: the demo's benchmark, one run on every model
 * the deployment can call, on the query the demo ships for it ({@link #QUERY_RESOURCE}), judged
 * by the strongest model the deployment serves; then each grade's order by {@link GradeOrder},
 * written to the catalog file in one edit. A catalog a discovery has just created has no order,
 * and the runtime would serve each grade with its cheapest entry; this puts the entries that
 * were just seen doing the agent's work first.
 *
 * <p>A race that cannot rank anything (nothing callable, the reference run failing, the judge
 * scoring nothing) writes no order and says why on its outcome; the catalog stays as the
 * discovery wrote it.
 *
 * @author Andrey Santrosyan
 * @since 0.1.5 (2026-11-23)
 */
public final class GradeOrderRace {
    private static final Logger log = LoggerFactory.getLogger(GradeOrderRace.class);

    private GradeOrderRace() {}

    /** The query every model answers, a classpath resource so its text has one home. */
    static final String QUERY_RESOURCE = "ai/redouble/demo/grade-order-query.txt";

    /**
     * Runs the race on the running dispatcher and writes the orders. Blocks until the race is
     * over, about as long as one agent run on the slowest model.
     *
     * @param catalog the demo's catalog, whose file takes the orders
     * @param owner   the user the race's workflow belongs to
     */
    public static GradeOrderOutcome run(DemoCatalog catalog, String owner) {
        List<ModelSpec> raced = DemoBenchmark.everyRung();
        if (raced.isEmpty()) {
            return failed(null, 0, "no graded model is callable, so there is nothing to order");
        }
        DemoBenchmark benchmark = new DemoBenchmark(Job.workflow(owner, "grade-order"), 1);
        benchmark.setCandidates(raced);
        DemoQuery query = new DemoQuery();
        query.setQuery(query());
        benchmark.setInput(query);
        try {
            JobDispatcher.getInstance().submit(benchmark).get();
        }
        catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("The race that orders the grades failed; the catalog keeps no order", cause);
            return failed(null, raced.size(), cause.getMessage());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while the race that orders the grades ran", e);
        }
        GradeOrderOutcome outcome = rank(benchmark.report(), raced.size());
        if (outcome.failure() == null) {
            Map<Grade, List<String>> orders = new LinkedHashMap<>();
            outcome.orders().forEach((grade, ids) -> orders.put(Grade.valueOf(grade), ids));
            catalog.orders(orders);
        }
        return outcome;
    }

    /** The race's report read as the grades' orders: what {@link #run} writes, or why nothing can be written. */
    static GradeOrderOutcome rank(BenchmarkReport report, int raced) {
        List<GradeOrder.Measured> measured = new ArrayList<>();
        Map<String, Double> scores = new LinkedHashMap<>();
        Double cost = null;
        String currency = null;
        for (BenchmarkReport.Row row : report.getRows()) {
            if (row.getCost() != null) {
                cost = (cost != null ? cost : 0.0) + row.getCost();
                currency = row.getCurrency();
            }
        }
        for (BenchmarkReport.Summary summary : report.getSummaries()) {
            if (summary.isReference()) {
                continue;
            }
            ModelSpec spec = Models.spec(summary.getModelId());
            measured.add(new GradeOrder.Measured(spec.getId(), spec.getGrade(), spec.getIdentity(), summary.getFailed(),
                    summary.getMeanJudgeScore(), summary.getMeanCost()));
            if (summary.getFailed() == 0 && summary.getMeanJudgeScore() != null) {
                scores.put(spec.getId(), summary.getMeanJudgeScore());
            }
        }
        Map<Grade, List<String>> orders = GradeOrder.of(measured);
        if (orders.isEmpty()) {
            return failed(report.getJudgeModel(), raced, report.getJudgeFailure() != null ? report.getJudgeFailure()
                    : "no model's run was scored, so there is nothing to order");
        }
        Map<String, List<String>> written = new LinkedHashMap<>();
        orders.forEach((grade, ids) -> written.put(grade.name(), ids));
        return new GradeOrderOutcome(report.getJudgeModel(), raced, raced - scores.size(), cost, currency, written, scores, null);
    }

    private static GradeOrderOutcome failed(String judge, int measured, String why) {
        return new GradeOrderOutcome(judge, measured, measured, null, null, Map.of(), Map.of(), why);
    }

    static String query() {
        try (InputStream in = GradeOrderRace.class.getClassLoader().getResourceAsStream(QUERY_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("The classpath does not carry " + QUERY_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
        catch (IOException e) {
            throw new UncheckedIOException("Could not read " + QUERY_RESOURCE, e);
        }
    }

    /** One line per fact of the outcome, as the discovery command prints it after its own report. */
    public static String describe(GradeOrderOutcome outcome) {
        StringBuilder sb = new StringBuilder();
        if (outcome.failure() != null) {
            return sb.append("Grades not ordered: ").append(outcome.failure()).toString();
        }
        sb.append("Benchmark: ").append(outcome.measured()).append(" callable models, 1 run each, judged by ").append(outcome.judge());
        if (outcome.cost() != null) {
            sb.append(", ").append(String.format(Locale.ROOT, "%.4f", outcome.cost())).append(' ').append(outcome.currency());
        }
        sb.append('\n').append("Order written for ").append(outcome.orders().size()).append(" grades");
        if (outcome.leftOut() > 0) {
            sb.append("; ").append(outcome.leftOut()).append(" models are in no order, their run failed or was not scored");
        }
        outcome.orders().forEach((grade, ids) -> {
            sb.append('\n').append("  ").append(grade).append(':');
            for (String id : ids) {
                sb.append(' ').append(id).append(" (").append(outcome.scores().get(id)).append(')');
            }
        });
        return sb.toString();
    }
}
