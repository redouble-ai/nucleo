/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two predicates on {@link ThinkingMode}. {@code thinkingActive}: a native thinking
 * block is guaranteed exactly when the model has an Anthropic thinking mode (EXTENDED or
 * ADAPTIVE) and the depth is not IMMEDIATE. {@code reasoningReserved}: headroom is booked
 * for the Anthropic modes exactly when {@code thinkingActive} holds, and for
 * REASONING_EFFORT at every depth. A null model answers false to both.
 * (REASONING_EFFORT's own translation rules are pinned in {@code OutputTranslationCatalogTest}.)
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-18)
 */
class ThinkingModePredicatesTest {

    private static ModelSpec withMode(ThinkingMode mode) {
        StandardModelSpec spec = new StandardModelSpec();
        spec.setId("predicate-test");
        spec.setThinkingMode(mode);
        return spec;
    }

    @Test
    void anAnthropicThinkingModeAboveImmediateGuaranteesANativeThinkingBlock() {
        assertTrue(ThinkingMode.thinkingActive(withMode(ThinkingMode.EXTENDED), Depth.STANDARD),
                "EXTENDED above IMMEDIATE returns a native thinking block");
        assertTrue(ThinkingMode.thinkingActive(withMode(ThinkingMode.ADAPTIVE), Depth.THOROUGH),
                "ADAPTIVE above IMMEDIATE returns a native thinking block");
        assertTrue(ThinkingMode.reasoningReserved(withMode(ThinkingMode.EXTENDED), Depth.STANDARD),
                "an Anthropic mode books headroom exactly when its thinking block is active");
    }

    @Test
    void immediateDepthAndNonThinkingModesGetNoBlock() {
        assertFalse(ThinkingMode.thinkingActive(withMode(ThinkingMode.EXTENDED), Depth.IMMEDIATE),
                "nothing at IMMEDIATE depth");
        assertFalse(ThinkingMode.thinkingActive(withMode(ThinkingMode.NONE), Depth.THOROUGH),
                "a model that does not reason returns no block at any depth");
        assertFalse(ThinkingMode.reasoningReserved(withMode(ThinkingMode.EXTENDED), Depth.IMMEDIATE),
                "an Anthropic mode books nothing at IMMEDIATE");
    }

    @Test
    void aNullModelAnswersFalseToBoth() {
        assertFalse(ThinkingMode.thinkingActive(null, Depth.STANDARD), "no model, no thinking block");
        assertFalse(ThinkingMode.reasoningReserved(null, Depth.STANDARD), "no model, no headroom");
    }
}
