/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

import ai.redouble.nucleo.harness.models.*;

import java.util.*;

/**
 * The order of each grade, read off one measured race: the rule a first discovery writes the
 * grades' orders by. Within a grade the entries stand by the judge's mean score, highest first,
 * and by the mean cost of a run, cheapest first, where the scores are equal. Then the versions
 * of one model are put newest first in the places that model's entries earned, so a newer
 * version stands above an older one even where the older one scored higher or cost less. An
 * entry with a failed run, or one the judge gave no score, is not in the order at all, which
 * is the one way an older version comes to stand in for a newer one.
 *
 * <p>Which entries are versions of one model is {@link ModelLineage}'s reading of the catalog
 * identity: {@code opus-5.5} and {@code opus-5} are one family, {@code gemma-3-12b-it} and
 * {@code gemma-3-27b-it} are two.
 *
 * @author Andrey Santrosyan
 * @since 0.1.5 (2026-11-23)
 */
public final class GradeOrder {
    private GradeOrder() {}

    /**
     * One entry as the race measured it.
     *
     * @param id       the catalog entry
     * @param grade    the entry's grade
     * @param identity the entry's catalog identity, which names its family and version
     * @param failed   how many of its runs failed
     * @param score    the judge's mean score over its runs, null when the judge scored none
     * @param cost     the mean cost of a run, null when no call of it was priced
     */
    public record Measured(String id, Grade grade, String identity, int failed, Double score, Double cost) {
        boolean qualifies() {
            return failed == 0 && score != null;
        }
    }

    /** Score, highest first; then cost, cheapest first, an unpriced entry after every priced one. */
    private static final Comparator<Measured> BY_SCORE_THEN_COST = Comparator.comparing(Measured::score, Comparator.reverseOrder())
            .thenComparing(Measured::cost, Comparator.nullsLast(Comparator.naturalOrder()));

    /** The grades' orders, every grade that has a qualifying entry and no other, in the rungs' own order. */
    public static Map<Grade, List<String>> of(List<Measured> measured) {
        Map<Grade, List<String>> orders = new LinkedHashMap<>();
        for (Grade grade : Grade.rungs()) {
            List<Measured> ranked = new ArrayList<>();
            for (Measured entry : measured) {
                if (entry.grade() == grade && entry.qualifies()) {
                    ranked.add(entry);
                }
            }
            if (ranked.isEmpty()) {
                continue;
            }
            ranked.sort(BY_SCORE_THEN_COST);
            newestFirst(ranked);
            List<String> ids = new ArrayList<>();
            for (Measured entry : ranked) {
                ids.add(entry.id());
            }
            orders.put(grade, ids);
        }
        return orders;
    }

    /**
     * Within each family, the newest version takes the family's best place, the next newest its
     * next, and so on; entries of one version keep the order their scores gave them, and every
     * other entry stays where it was.
     */
    private static void newestFirst(List<Measured> ranked) {
        Map<String, List<Integer>> places = new LinkedHashMap<>();
        for (int i = 0; i < ranked.size(); i++) {
            places.computeIfAbsent(ModelLineage.of(ranked.get(i).identity()).family(), family -> new ArrayList<>()).add(i);
        }
        for (List<Integer> own : places.values()) {
            if (own.size() < 2) {
                continue;
            }
            List<Measured> versions = new ArrayList<>();
            for (int place : own) {
                versions.add(ranked.get(place));
            }
            versions.sort((a, b) -> ModelLineage.of(b.identity()).compareVersion(ModelLineage.of(a.identity())));
            for (int i = 0; i < own.size(); i++) {
                ranked.set(own.get(i), versions.get(i));
            }
        }
    }
}
