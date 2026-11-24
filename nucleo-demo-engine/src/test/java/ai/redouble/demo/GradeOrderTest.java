/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.tools.benchmark.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The rule a first discovery orders the grades by ({@link GradeOrder}), and the reading of a
 * benchmark's report into that rule's input ({@link GradeOrderRace#rank}).
 *
 * @author Andrey Santrosyan
 * @since 0.1.5 (2026-11-23)
 */
class GradeOrderTest {

    private static GradeOrder.Measured ran(String id, Grade grade, String identity, Double score, Double cost) {
        return new GradeOrder.Measured(id, grade, identity, 0, score, cost);
    }

    @Test
    void theBestScoreComesFirstAndPriceOnlyBreaksATie() {
        Map<Grade, List<String>> orders = GradeOrder.of(List.of(
                ran("cheap-weak", Grade.SMALL, "alpha-1", 4.0, 0.001),
                ran("dear-strong", Grade.SMALL, "beta-1", 9.0, 0.050),
                ran("tie-dear", Grade.SMALL, "gamma-1", 7.0, 0.020),
                ran("tie-cheap", Grade.SMALL, "delta-1", 7.0, 0.002),
                ran("tie-unpriced", Grade.SMALL, "epsilon-1", 7.0, null)));
        assertEquals(List.of("dear-strong", "tie-cheap", "tie-dear", "tie-unpriced", "cheap-weak"), orders.get(Grade.SMALL),
                "score first; among equal scores the cheaper run, and an unpriced one after every priced one");
    }

    @Test
    void aNewerVersionStandsAboveAnOlderOneAgainstScoreAndAgainstPrice() {
        Map<Grade, List<String>> orders = GradeOrder.of(List.of(
                ran("haiku-old", Grade.SMALL, "haiku-4.5", 10.0, 0.001),
                ran("other", Grade.SMALL, "nemotron-super-3-120b", 8.0, 0.001),
                ran("haiku-new", Grade.SMALL, "haiku-5.5", 6.0, 0.010)));
        assertEquals(List.of("haiku-new", "other", "haiku-old"), orders.get(Grade.SMALL),
                "the newer version takes the family's best place though it scored lower and cost more; the others keep theirs");
    }

    @Test
    void entriesOfOneVersionKeepTheOrderTheirScoresGaveThem() {
        Map<Grade, List<String>> orders = GradeOrder.of(List.of(
                ran("opus-5-a", Grade.XL, "opus-5", 9.5, 0.09),
                ran("opus-55-b", Grade.XL, "opus-5.5", 9.0, 0.08),
                ran("opus-55-a", Grade.XL, "opus-5.5", 8.0, 0.08)));
        assertEquals(List.of("opus-55-b", "opus-55-a", "opus-5-a"), orders.get(Grade.XL));
    }

    @Test
    void aFailedNewerVersionIsLeftOutAndTheOlderOneStandsInForIt() {
        Map<Grade, List<String>> orders = GradeOrder.of(List.of(
                new GradeOrder.Measured("sonnet-new", Grade.MEDIUM, "sonnet-5.5", 1, null, null),
                ran("sonnet-old", Grade.MEDIUM, "sonnet-5", 9.0, 0.02),
                ran("unscored", Grade.MEDIUM, "zeta-1", null, 0.01)));
        assertEquals(List.of("sonnet-old"), orders.get(Grade.MEDIUM), "a failed run and an unscored one are in no order");
    }

    @Test
    void sizesOfOneModelAreDifferentFamiliesAndStandByScore() {
        Map<Grade, List<String>> orders = GradeOrder.of(List.of(
                ran("gemma-12", Grade.SMALL, "gemma-3-12b-it", 9.0, 0.001),
                ran("gemma-27", Grade.SMALL, "gemma-3-27b-it", 3.0, 0.001)));
        assertEquals(List.of("gemma-12", "gemma-27"), orders.get(Grade.SMALL));
    }

    @Test
    void aGradeWithNothingThatQualifiesHasNoOrderAndGradesComeInTheRungsOrder() {
        Map<Grade, List<String>> orders = GradeOrder.of(List.of(
                ran("big", Grade.XL, "opus-5", 9.0, 0.09),
                new GradeOrder.Measured("broken", Grade.MEDIUM, "sonnet-5", 1, null, null),
                ran("small", Grade.MICRO, "nova-micro", 5.0, 0.001)));
        assertEquals(List.of(Grade.MICRO, Grade.XL), new ArrayList<>(orders.keySet()));
        assertFalse(orders.containsKey(Grade.MEDIUM));
    }

    private static BenchmarkReport.Summary summary(String id, boolean reference, int failed, Double score, Double cost) {
        BenchmarkReport.Summary summary = new BenchmarkReport.Summary();
        summary.setModelId(id);
        summary.setReference(reference);
        summary.setRuns(1);
        summary.setFailed(failed);
        summary.setMeanJudgeScore(score);
        summary.setMeanCost(cost);
        return summary;
    }

    private static BenchmarkReport.Row row(Double cost) {
        BenchmarkReport.Row row = new BenchmarkReport.Row();
        row.setCost(cost);
        row.setCurrency("USD");
        return row;
    }

    /** Against the suite's catalog: nova-micro is MICRO, haiku-4.5 SMALL, sonnet-5 MEDIUM, opus-5 XL. */
    @Test
    void aReportIsReadAsOrdersScoresTheRacesCostAndTheModelsLeftOut() {
        BenchmarkReport report = new BenchmarkReport();
        report.setJudgeModel("claude-opus-5-bedrock");
        report.getSummaries().add(summary("claude-opus-5-bedrock", true, 0, null, 0.09));
        report.getSummaries().add(summary("nova-micro", false, 0, 6.5, 0.001));
        report.getSummaries().add(summary("claude-haiku-4-5-bedrock", false, 0, 9.0, 0.01));
        report.getSummaries().add(summary("claude-sonnet-5-bedrock", false, 1, null, null));
        report.getRows().add(row(0.09));
        report.getRows().add(row(0.001));
        report.getRows().add(row(0.01));
        report.getRows().add(row(null));
        GradeOrderOutcome outcome = GradeOrderRace.rank(report, 3);
        assertNull(outcome.failure());
        assertEquals("claude-opus-5-bedrock", outcome.judge());
        assertEquals(3, outcome.measured());
        assertEquals(1, outcome.leftOut(), "the failed run is in no order");
        assertEquals(Map.of("MICRO", List.of("nova-micro"), "SMALL", List.of("claude-haiku-4-5-bedrock")), outcome.orders());
        assertEquals(Map.of("nova-micro", 6.5, "claude-haiku-4-5-bedrock", 9.0), outcome.scores());
        assertEquals(0.101, outcome.cost(), 1e-9, "every priced run of the race, the reference's included");
        assertEquals("USD", outcome.currency());
        String printed = GradeOrderRace.describe(outcome);
        assertTrue(printed.contains("Benchmark: 3 callable models, 1 run each, judged by claude-opus-5-bedrock"), printed);
        assertTrue(printed.contains("Order written for 2 grades"), printed);
        assertTrue(printed.contains("SMALL: claude-haiku-4-5-bedrock (9.0)"), printed);
    }

    @Test
    void aRaceThatScoredNothingWritesNoOrderAndSaysWhy() {
        BenchmarkReport report = new BenchmarkReport();
        report.setJudgeFailure("the reference run failed, so there is nothing to judge against: refused");
        report.getSummaries().add(summary("nova-micro", false, 0, null, 0.001));
        GradeOrderOutcome outcome = GradeOrderRace.rank(report, 1);
        assertEquals("the reference run failed, so there is nothing to judge against: refused", outcome.failure());
        assertTrue(outcome.orders().isEmpty());
        assertEquals("Grades not ordered: " + outcome.failure(), GradeOrderRace.describe(outcome));
    }

    @Test
    void theQueryEveryModelAnswersShipsWithTheEngine() {
        assertFalse(GradeOrderRace.query().isBlank());
    }
}
