/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import ai.redouble.nucleo.harness.errors.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How a decision seat resolves: through the picker's decision declaration and the gate, never
 * through {@code provide}, with the kind wall refusing an entry of another family on either
 * side, a picker declaring none refused by name, and a pinned decision binding that names a
 * spec of another kind refused at mint. Unlike embeddings the declaration is not frozen.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
class DecisionResolutionTest {

    static StandardModelSpec decision(String id) {
        StandardModelSpec spec = new StandardModelSpec();
        spec.setId(id);
        spec.setIdentity(id);
        spec.setProviderKey("systemone-decision");
        spec.setWireModelId("kev-latest");
        spec.setMaxContextTokens(4096);
        spec.setMaxConcurrent(1);
        return spec;
    }

    private static Seat seat() {
        return new Seat(DecisionResolutionTest.class, null, ModelKind.DECISION);
    }

    private static Situation permitting() {
        Situation situation = new Situation();
        situation.setEnvelope(spec -> true);
        return situation;
    }

    @BeforeEach
    @AfterEach
    void reset() {
        ModelPickers.resetForTests();
    }

    @Test
    void theDeclarationResolves_andMayChangeBetweenCalls() {
        StandardModelSpec first = decision("kev-a");
        StandardModelSpec second = decision("kev-b");
        ModelPicker declaring = new TestModelPicker() {
            @Override
            public ModelSpec decisionSpec() {
                return first;
            }
        };
        assertEquals("kev-a", ModelPickers.resolveDecisionWith(declaring, seat(), permitting()).getId());
        ModelPicker swapped = new TestModelPicker() {
            @Override
            public ModelSpec decisionSpec() {
                return second;
            }
        };
        assertEquals("kev-b", ModelPickers.resolveDecisionWith(swapped, seat(), permitting()).getId(),
                "nothing stored depends on which decision model answered, so the declaration is not frozen");
    }

    @Test
    void aPickerDeclaringNoDecisionModelIsRefusedByName() {
        ModelPicker silent = new TestModelPicker() {
            @Override
            public ModelSpec decisionSpec() {
                return null;
            }
        };
        UncorrectableRuntimeLLMException refusal = assertThrows(UncorrectableRuntimeLLMException.class,
                () -> ModelPickers.resolveDecisionWith(silent, seat(), permitting()));
        assertTrue(refusal.getMessage().contains("no decision spec"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains(DecisionResolutionTest.class.getSimpleName()), refusal.getMessage());
    }

    @Test
    void theKindWallHoldsOnBothSides() {
        ModelPicker wrongKind = new TestModelPicker() {
            @Override
            public ModelSpec decisionSpec() {
                return TestModels.embeddings();
            }
        };
        UncorrectableRuntimeLLMException refusal = assertThrows(UncorrectableRuntimeLLMException.class,
                () -> ModelPickers.resolveDecisionWith(wrongKind, seat(), permitting()));
        assertTrue(refusal.getMessage().contains("a decision seat must resolve to an entry of its own kind"), refusal.getMessage());
        UncorrectableRuntimeLLMException pinned = assertThrows(UncorrectableRuntimeLLMException.class,
                () -> ModelPickers.resolvePinned(decision("kev-x"), new Seat(DecisionResolutionTest.class, Grade.SMALL, ModelKind.LLM), permitting()));
        assertTrue(pinned.getMessage().contains("an LLM seat must resolve to an entry of its own kind"), pinned.getMessage());
        UncorrectableRuntimeLLMException viaPicker = assertThrows(UncorrectableRuntimeLLMException.class,
                () -> ModelPickers.resolveWith(new TestModelPicker(), seat(), permitting()));
        assertTrue(viaPicker.getMessage().contains("cannot resolve through the picker"), viaPicker.getMessage());
    }

    @Test
    void aPinnedDecisionBindingNamesADecisionSpec() {
        assertThrows(IllegalArgumentException.class, () -> ModelBinding.decisionPinned(TestModels.embeddings(), 10));
        assertThrows(IllegalArgumentException.class, () -> new ModelBinding(decision("kev-x"), Depth.STANDARD), "an LLM pin refuses a decision spec");
        ModelBinding binding = ModelBinding.decisionPinned(decision("kev-x"), 10);
        assertEquals(ModelKind.DECISION, binding.getKind());
        assertTrue(binding.isDecision());
        assertTrue(binding.isPinned());
        binding.resolve(binding.getPinnedSpec());
        assertEquals(10, binding.price(), "a decision reserves its input alone");
        assertEquals(0, binding.getReservedOutput());
        ModelBinding counted = ModelBinding.decision("{\"state\":\"twelve words of state to count under the tokenizer\"}");
        counted.resolve(decision("kev-y"));
        assertTrue(counted.price() > 0, "the counted form prices the text");
        StandardModelSpec tiny = decision("kev-z");
        tiny.setMaxContextTokens(2);
        ModelBinding overflow = ModelBinding.decision(3);
        overflow.resolve(tiny);
        String refusal = assertThrows(UncorrectableRuntimeLLMException.class, overflow::price).getMessage();
        assertTrue(refusal.contains("exceeds the context window"), refusal);
        assertTrue(refusal.contains("3 tokens") && refusal.contains("(2)"),
                "the counts are named, the input and the ceiling: " + refusal);
    }
}
