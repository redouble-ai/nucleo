/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import ai.redouble.nucleo.*;
import ai.redouble.nucleo.harness.errors.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the catalog's translation of the seat vocabulary into numbers: the framework tables
 * for output rungs and thinking depths, the per-entry overrides, their all-or-none
 * validation at load, and the comfort window declaration.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-06)
 */
class OutputTranslationCatalogTest {

    private static StandardModelSpec plain(int maxOutput, ThinkingMode mode) {
        StandardModelSpec spec = new StandardModelSpec();
        spec.setId("plain-fake");
        spec.setIdentity("plain-fake");
        spec.setProviderKey("openai");
        spec.setWireModelId("plain-fake");
        spec.setMaxContextTokens(200_000);
        spec.setMaxOutputTokens(maxOutput);
        spec.setThinkingMode(mode);
        spec.setTpm(1000);
        return spec;
    }

    @Test
    void frameworkTable_translatesEveryRung_cappedAtTheCeiling() {
        ModelSpec big = plain(128_000, ThinkingMode.NONE);
        assertEquals(1_024, big.getOutputBudget(OutputSize.VERDICT));
        assertEquals(4_096, big.getOutputBudget(OutputSize.COMPACT));
        assertEquals(16_384, big.getOutputBudget(OutputSize.STANDARD));
        assertEquals(32_768, big.getOutputBudget(OutputSize.EXTENDED));
        assertEquals(128_000, big.getOutputBudget(OutputSize.MAX), "MAX is the ceiling itself");
        ModelSpec small = plain(10_000, ThinkingMode.NONE);
        assertEquals(10_000, small.getOutputBudget(OutputSize.STANDARD), "a rung never exceeds the ceiling");
        assertEquals(10_000, small.getOutputBudget(OutputSize.MAX));
    }

    @Test
    void thinkingLadder_scalesToTheCeiling_withTheAnthropicFloor() {
        ModelSpec spec = plain(64_000, ThinkingMode.EXTENDED);
        assertEquals(0, spec.getThinkingBudget(Depth.IMMEDIATE));
        assertEquals(4_000, spec.getThinkingBudget(Depth.QUICK));
        assertEquals(8_000, spec.getThinkingBudget(Depth.STANDARD));
        assertEquals(16_000, spec.getThinkingBudget(Depth.THOROUGH));
        assertEquals(16_000, spec.getThinkingBudget(Depth.ULTRA_THOROUGH));
        assertEquals(AbstractModelSpec.MIN_THINKING_BUDGET, plain(10_000, ThinkingMode.EXTENDED).getThinkingBudget(Depth.QUICK),
                "the ladder floors at what Anthropic accepts");
    }

    @Test
    void reasoningEffortModel_booksHeadroomEvenAtImmediate() {
        ModelSpec spec = plain(128_000, ThinkingMode.REASONING_EFFORT);
        assertEquals(AbstractModelSpec.MIN_THINKING_BUDGET, spec.getThinkingBudget(Depth.IMMEDIATE),
                "the model always reasons; IMMEDIATE is its lowest effort, and the floor is the headroom it still needs");
        assertTrue(ThinkingMode.reasoningReserved(spec, Depth.IMMEDIATE), "headroom is booked at every depth");
        assertFalse(ThinkingMode.thinkingActive(spec, Depth.STANDARD), "but no native thinking block comes back, so the prose field stays");
        ModelSpec none = plain(128_000, ThinkingMode.NONE);
        assertFalse(ThinkingMode.reasoningReserved(none, Depth.THOROUGH), "a model that does not reason books nothing");
    }

    @Test
    void entryOverrides_replaceTheTables_andLoadTheComfortWindow() {
        ModelsBackend backend = new JsonModelsBackend("/models-translations.json");
        ModelSpec tuned = backend.spec("tuned");
        assertEquals(2_000, tuned.getOutputBudget(OutputSize.VERDICT));
        assertEquals(6_000, tuned.getOutputBudget(OutputSize.COMPACT));
        assertEquals(20_000, tuned.getOutputBudget(OutputSize.STANDARD));
        assertEquals(40_000, tuned.getOutputBudget(OutputSize.EXTENDED));
        assertEquals(64_000, tuned.getOutputBudget(OutputSize.MAX), "MAX stays the ceiling under an override");
        assertEquals(2_048, tuned.getThinkingBudget(Depth.QUICK));
        assertEquals(4_096, tuned.getThinkingBudget(Depth.STANDARD));
        assertEquals(12_000, tuned.getThinkingBudget(Depth.THOROUGH));
        assertEquals(24_000, tuned.getThinkingBudget(Depth.ULTRA_THOROUGH));
        assertEquals(0, tuned.getThinkingBudget(Depth.IMMEDIATE), "IMMEDIATE is never declared: it is no thinking on an Anthropic mode");
        assertEquals(150_000, tuned.getComfortContextTokens());
        ModelSpec untuned = backend.spec("untuned");
        assertEquals(4_096, untuned.getOutputBudget(OutputSize.COMPACT), "an entry without overrides rides the framework table");
        assertNull(untuned.getComfortContextTokens());
    }

    @Test
    void partialOrContradictoryOverrides_failAtLoad() {
        assertLoadFails("/models-thinking-partial.json", "thinking_budgets");
        assertLoadFails("/models-thinking-none.json", "thinking_mode NONE");
        assertLoadFails("/models-output-partial.json", "output_budgets");
        assertLoadFails("/models-comfort-over.json", "comfort_context_tokens");
    }

    @Test
    void reasoningEffortOverrides_nameAllFiveDepths() {
        // A reasoning-effort model reasons at IMMEDIATE too, so its table declares that
        // depth alongside the four; an Anthropic-mode table never does (see the test above).
        ModelsBackend backend = new JsonModelsBackend("/models-thinking-effort.json");
        ModelSpec tuned = backend.spec("effort-tuned");
        assertEquals(1_500, tuned.getThinkingBudget(Depth.IMMEDIATE),
                "the declared IMMEDIATE headroom replaces the ladder's floor");
        assertEquals(24_000, tuned.getThinkingBudget(Depth.ULTRA_THOROUGH));
        assertLoadFails("/models-thinking-effort-four.json", "thinking_budgets");
    }

    private static void assertLoadFails(String resource, String namedField) {
        UncorrectableRuntimeLLMException ex = assertThrows(UncorrectableRuntimeLLMException.class,
                () -> new JsonModelsBackend(resource), resource);
        assertTrue(ex.getMessage().contains(namedField), resource + ": " + ex.getMessage());
    }
}
