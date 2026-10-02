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
 * Declaring the deployment's picker as an instance: {@code ModelPickers.use} before the first
 * resolution is the picker every resolution goes through; a second declaration, or one after
 * the configured class has already answered, is refused rather than switching policy under
 * running work.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-11)
 */
class ModelPickerDeclarationTest {

    private static Situation permitting() {
        Situation situation = new Situation();
        situation.setEnvelope(spec -> true);
        return situation;
    }

    @BeforeEach
    @AfterEach
    void thaw() {
        ModelPickers.resetForTests();
    }

    @Test
    void aDeclaredInstanceServesEveryResolution() {
        ModelSpec small = TestModels.small();
        ModelPicker declared = (seat, situation) -> small;
        ModelPickers.use(declared);
        assertEquals(small.getId(), ModelPickers.resolve(new Seat(ModelPickerDeclarationTest.class, Grade.SMALL, ModelKind.LLM), permitting()).getId());
    }

    @Test
    void aSecondDeclarationIsRefused() {
        ModelPickers.use(new TestModelPicker());
        UncorrectableRuntimeLLMException ex = assertThrows(UncorrectableRuntimeLLMException.class,
                () -> ModelPickers.use(new TestModelPicker()));
        assertTrue(ex.getMessage().contains("once"), ex.getMessage());
    }

    @Test
    void aDeclarationAfterTheFirstResolutionIsRefused() {
        // The configured class (the suite's TestModelPicker) answered already; policy is set.
        ModelPickers.resolve(new Seat(ModelPickerDeclarationTest.class, Grade.SMALL, ModelKind.LLM), permitting());
        UncorrectableRuntimeLLMException ex = assertThrows(UncorrectableRuntimeLLMException.class,
                () -> ModelPickers.use(new TestModelPicker()));
        assertTrue(ex.getMessage().contains(TestModelPicker.class.getName()), ex.getMessage());
    }

    @Test
    void nullIsNotADeclaration() {
        assertThrows(UncorrectableRuntimeLLMException.class, () -> ModelPickers.use(null));
    }
}
