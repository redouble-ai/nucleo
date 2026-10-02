/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models.discovery;

import ai.redouble.nucleo.harness.models.*;

import java.util.*;

/**
 * The rule that puts a model on a rung of the {@link Grade} ladder: the vendor's own tier, then
 * the model's generation, never its price.
 *
 * <p>Every vendor sorts its models into tiers and names them so. The tier word in a listed name
 * is a fact on the listing, and it decides the rung by itself: {@code nano}, {@code micro},
 * {@code lite} and {@code tiny} are MICRO; {@code mini}, {@code small}, {@code haiku},
 * {@code flash} and {@code luna} are SMALL; {@code medium}, {@code sonnet} and {@code terra}
 * are MEDIUM; {@code large} is LARGE. A name carrying two tier words ({@code flash-lite}) takes
 * the lower rung. A flagship tier word ({@code sol}) decides nothing by itself, since a
 * flagship's rung follows its generation. A name with no deciding tier word says nothing
 * here, and its rung is the classifier's judgment under the rest of
 * the rule, stated in {@link ModelClassifier#INSTRUCTIONS}: an open-weight model by its
 * parameters, the active ones per token for a mixture of experts, never the total; a vendor's
 * current flagship is XL, a flagship one generation behind is LARGE, and MEGA is above the
 * flagship; an expensive old model is an old model.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-29)
 */
public final class GradeCriterion {
    private GradeCriterion() {}

    /** The tier words vendors put in their model names, each with the rung it decides. */
    static final Map<String, Grade> TIER_WORDS = Map.ofEntries(
            Map.entry("nano", Grade.MICRO), Map.entry("micro", Grade.MICRO), Map.entry("lite", Grade.MICRO), Map.entry("tiny", Grade.MICRO),
            Map.entry("mini", Grade.SMALL), Map.entry("small", Grade.SMALL), Map.entry("haiku", Grade.SMALL), Map.entry("flash", Grade.SMALL), Map.entry("luna", Grade.SMALL),
            Map.entry("medium", Grade.MEDIUM), Map.entry("sonnet", Grade.MEDIUM), Map.entry("terra", Grade.MEDIUM),
            Map.entry("large", Grade.LARGE));

    /** The tier word in a listed model name that decides its rung, the lowest when it carries two; null when it carries none. */
    public static String tierWord(String wireModelId) {
        String word = null;
        for (String token : wireModelId.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            Grade grade = TIER_WORDS.get(token);
            if (grade != null && (word == null || grade.compareTo(TIER_WORDS.get(word)) < 0)) {
                word = token;
            }
        }
        return word;
    }

    /** The rung a listed model name's tier word decides, or null when the name carries no tier word. */
    public static Grade fromName(String wireModelId) {
        String word = tierWord(wireModelId);
        return word != null ? TIER_WORDS.get(word) : null;
    }
}
