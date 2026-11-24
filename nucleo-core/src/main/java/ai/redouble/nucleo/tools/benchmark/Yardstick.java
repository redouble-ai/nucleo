/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.benchmark;

import ai.redouble.nucleo.harness.models.*;

import java.util.*;

/**
 * Which entry of the strongest grade a benchmark measures against, when the deployment has put
 * that grade in no order: the reference run's model and the judge's. With no order the picker
 * serves a grade with its cheapest entry, which is the wrong end of the grade to judge from.
 *
 * <p>The entries are ranked, and the benchmark takes them from the first:
 * <ol>
 *   <li>an entry of a preferred vendor before any other;</li>
 *   <li>the newest version of a model before an older version of it ({@link ModelLineage});</li>
 *   <li>the dearer list price, input plus output, before the cheaper, an unpriced entry last;</li>
 *   <li>the catalog id, so equal entries always rank the same way.</li>
 * </ol>
 * Every rule ranks and none excludes. A grade with no entry of a preferred vendor is ranked by
 * version and price alone, a catalog with no prices by vendor and version, and every entry of
 * the grade is in the result, so the benchmark has a next one to turn to when the first does
 * not answer.
 *
 * @author Andrey Santrosyan
 * @since 0.1.5 (2026-11-23)
 */
public final class Yardstick {
    private Yardstick() {}

    /**
     * One entry of the grade.
     *
     * @param id       the catalog entry
     * @param identity the entry's catalog identity, which names its family and version
     * @param vendor   who made the model, or null when its provider cannot tell
     * @param price    the list price per million, input plus output, or null when unpriced
     */
    public record Candidate(String id, String identity, String vendor, Double price) {}

    /** The candidates' ids, the one to measure against first. */
    public static List<String> rank(List<Candidate> field, List<String> preferredVendors) {
        Map<String, ModelLineage> newest = new HashMap<>();
        for (Candidate candidate : field) {
            ModelLineage lineage = ModelLineage.of(candidate.identity());
            newest.merge(lineage.family(), lineage, (held, other) -> other.compareVersion(held) > 0 ? other : held);
        }
        List<Candidate> ranked = new ArrayList<>(field);
        ranked.sort(Comparator.comparing((Candidate c) -> c.vendor() == null || !preferredVendors.contains(c.vendor()))
                .thenComparing(c -> {
                    ModelLineage lineage = ModelLineage.of(c.identity());
                    return lineage.compareVersion(newest.get(lineage.family())) != 0;
                })
                .thenComparing(Candidate::price, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Candidate::id));
        List<String> ids = new ArrayList<>();
        for (Candidate candidate : ranked) {
            ids.add(candidate.id());
        }
        return ids;
    }
}
