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
 * grades' orders by. Within a grade:
 * <ol>
 *   <li>The entries that answered the question, with a score of {@link #PASS} or more, stand
 *       before the ones that did not.</li>
 *   <li>Among those that answered, and again among those that did not, the familiar entries,
 *       those the catalog had a shape for, stand before the unfamiliar ones, whose grade is a
 *       model's guess made by this discovery. A guess can put a weak model in a strong grade,
 *       and one easy question does not tell them apart, so no score lifts an unfamiliar entry
 *       above a familiar one that answered. A familiar entry that did not answer leads
 *       nothing.</li>
 *   <li>Each of those groups stands by the judge's mean score, highest first. Scores within
 *       {@link #TIE} of the best of a tier are the same score: the best and everything that
 *       close to it tie, and the next score below starts the next tier.</li>
 *   <li>Within a tier the cheaper list price comes first, an unpriced entry last, then the
 *       catalog id.</li>
 *   <li>Then the versions of one model are put newest first in the places that model's
 *       entries earned, so a newer version stands above an older one even where the older one
 *       scored higher or costs less.</li>
 * </ol>
 * An entry with a failed run, or one the judge gave no score, is not in the order at all, which
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
     * @param familiar whether the catalog had a shape for the entry; false for one whose grade
     *                 this discovery's classifying model guessed
     * @param failed   how many of its runs failed
     * @param score    the judge's mean score over its runs, null when the judge scored none
     * @param price    the list price per million, input plus output, null when unpriced
     */
    public record Measured(String id, Grade grade, String identity, boolean familiar, int failed, Double score, Double price) {
        boolean qualifies() {
            return failed == 0 && score != null;
        }
    }

    /** How far below the best score of a tier a score still counts as that score, on the judge's scale of 10. */
    public static final double TIE = 0.5;
    /** The score an entry answered the question with: half of the judge's scale of 10. An entry under it stands behind every entry at or over it. */
    public static final double PASS = 5.0;
    /** Within a tier: list price, cheapest first, an unpriced entry after every priced one; then the id. */
    private static final Comparator<Measured> BY_PRICE = Comparator.comparing(Measured::price, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Measured::id);

    /** The grades' orders, every grade that has a qualifying entry and no other, in the rungs' own order. */
    public static Map<Grade, List<String>> of(List<Measured> measured) {
        Map<Grade, List<String>> orders = new LinkedHashMap<>();
        for (Grade grade : Grade.rungs()) {
            List<String> ids = new ArrayList<>();
            for (boolean passed : new boolean[]{true, false}) {
                for (boolean familiar : new boolean[]{true, false}) {
                    List<Measured> group = new ArrayList<>();
                    for (Measured entry : measured) {
                        if (entry.grade() == grade && entry.qualifies() && entry.familiar() == familiar && entry.score() >= PASS == passed) {
                            group.add(entry);
                        }
                    }
                    for (Measured entry : ranked(group)) {
                        ids.add(entry.id());
                    }
                }
            }
            if (!ids.isEmpty()) {
                orders.put(grade, ids);
            }
        }
        return orders;
    }

    /** One group in its order: tiers of tied scores from the best down, each tier by price, then the newest version of a model first. */
    private static List<Measured> ranked(List<Measured> group) {
        List<Measured> byScore = new ArrayList<>(group);
        byScore.sort(Comparator.comparing(Measured::score, Comparator.reverseOrder()));
        List<Measured> ranked = new ArrayList<>();
        List<Measured> tier = new ArrayList<>();
        for (Measured entry : byScore) {
            if (!tier.isEmpty() && entry.score() < tier.get(0).score() - TIE) {
                tier.sort(BY_PRICE);
                ranked.addAll(tier);
                tier.clear();
            }
            tier.add(entry);
        }
        tier.sort(BY_PRICE);
        ranked.addAll(tier);
        newestFirst(ranked);
        return ranked;
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
