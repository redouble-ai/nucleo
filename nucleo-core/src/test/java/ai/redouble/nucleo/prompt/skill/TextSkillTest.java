/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.prompt.skill;

import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins {@link TextSkill}'s construction normalization: null {@code resources} and
 * {@code suggestedTools} become immutable empties, and non-null collections are
 * immutably copied - consumers never null-check and can never mutate a skill's
 * collections after construction.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-18)
 */
class TextSkillTest {

    @Test
    void nullCollectionsNormalizeToImmutableEmpties() {
        TextSkill skill = new TextSkill("bare", null, null, null, null, null);
        assertEquals(Map.of(), skill.resources(), "a null resources map is an empty one, never a null-check");
        assertEquals(List.of(), skill.suggestedTools(), "a null tool list is an empty one");
        assertThrows(UnsupportedOperationException.class, () -> skill.suggestedTools().add("smuggled"),
                "the collections are immutable");
    }

    @Test
    void providedCollectionsAreImmutablyCopied() {
        List<String> mutable = new ArrayList<>(List.of("get_artifact_field"));
        TextSkill skill = new TextSkill("copied", null, null, Map.of(), mutable, null);
        mutable.add("added-after-construction");
        assertEquals(List.of("get_artifact_field"), skill.suggestedTools(),
                "a later mutation of the caller's list never reaches the skill");
        assertThrows(UnsupportedOperationException.class, () -> skill.resources().put("k", null));
    }
}
