/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models.discovery;

import ai.redouble.nucleo.harness.models.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The tier word in a listed model name decides its rung: the vendor's nano, micro, lite and
 * tiny tier is MICRO, mini, small, haiku, flash and luna SMALL, medium, sonnet and terra
 * MEDIUM, large LARGE; a name with two tier words takes the lower rung; a flagship tier word (sol)
 * decides nothing, since a flagship's rung follows its generation; a name with no deciding
 * word decides nothing, whatever size or version it carries, since a number in a name is a
 * total that may be a mixture of experts.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-29)
 */
class GradeCriterionTest {
    @Test
    void theVendorsTierWordDecidesTheRung() {
        assertEquals(Grade.MICRO, GradeCriterion.fromName("gpt-5.4-nano-2026-03-17"));
        assertEquals(Grade.MICRO, GradeCriterion.fromName("us.amazon.nova-micro-v1:0"));
        assertEquals(Grade.MICRO, GradeCriterion.fromName("amazon.nova-2-lite-v1:0"));
        assertEquals(Grade.SMALL, GradeCriterion.fromName("gpt-5.4-mini"));
        assertEquals(Grade.SMALL, GradeCriterion.fromName("gpt-5.6-luna"), "OpenAI's luna tier, the small one of a family with three");
        assertEquals(Grade.MEDIUM, GradeCriterion.fromName("gpt-5.6-terra"), "OpenAI's terra tier, the middle one of a family with three");
        assertEquals(Grade.SMALL, GradeCriterion.fromName("global.anthropic.claude-haiku-4-5-20251001-v1:0"));
        assertEquals(Grade.SMALL, GradeCriterion.fromName("mistral-small-2503"));
        assertEquals(Grade.MEDIUM, GradeCriterion.fromName("mistral-medium-3.5"));
        assertEquals(Grade.MEDIUM, GradeCriterion.fromName("us.anthropic.claude-sonnet-5"));
        assertEquals(Grade.LARGE, GradeCriterion.fromName("mistral-large-3"));
        assertEquals("nano", GradeCriterion.tierWord("gpt-5.4-nano-2026-03-17"), "the word, for the entry's note");
    }

    @Test
    void twoTierWordsTakeTheLowerRung() {
        assertEquals(Grade.MICRO, GradeCriterion.fromName("gemini-3-flash-lite"));
        assertEquals("lite", GradeCriterion.tierWord("gemini-3-flash-lite"));
    }

    @Test
    void aNameWithNoTierWordDecidesNothing() {
        assertNull(GradeCriterion.fromName("openai.gpt-oss-120b-1:0"), "a size in a name is a total that may be a mixture of experts: the classifier's judgment, by active parameters");
        assertNull(GradeCriterion.fromName("gpt-5.6"), "a flagship's rung follows its generation, which the name alone does not tell");
        assertNull(GradeCriterion.fromName("gpt-5.6-sol"), "the flagship tier's own word tells no more than the bare name");
        assertNull(GradeCriterion.fromName("ministral-3-14b-instruct"), "a word that merely contains a tier word is not that word");
        assertNull(GradeCriterion.tierWord("kimi-k3"));
    }
}
