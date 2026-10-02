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
 * The gate's grade floor is the picker's ceiling at most: a seat asking for a grade above
 * everything the deployment serves is lowered to the ceiling before the picker sees it,
 * so code written for the whole ladder runs on a deployment with one model; a picker that
 * returns less than its own ceiling for a seat is still a bug, and still refused.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class ModelPickersCeilingTest {

    @AfterEach
    void reset() {
        ModelPickers.resetForTests();
    }

    private static Situation situation() {
        Situation situation = new Situation();
        situation.setEnvelope(spec -> true);
        return situation;
    }

    /** A picker that serves one grade and declares it as its ceiling. */
    private static ModelPicker servingOnly(Grade served, ModelSpec spec) {
        return new ModelPicker() {
            @Override
            public ModelSpec provide(Seat seat, Situation situation) {
                assertEquals(served, seat.grade(), "the gate lowered the seat before the picker saw it");
                return spec;
            }

            @Override
            public Grade ceiling() {
                return served;
            }
        };
    }

    @Test
    void aSeatAboveEverythingTheDeploymentServesIsServedAtTheCeiling() {
        ModelPicker picker = servingOnly(Grade.SMALL, TestModels.small());
        ModelSpec served = ModelPickers.resolveWith(picker, new Seat(ModelPickersCeilingTest.class, Grade.MEGA, ModelKind.LLM), situation());
        assertEquals(TestModels.small().getId(), served.getId(), "the deployment's best is what a seat above it gets");
        assertEquals(TestModels.small().getId(),
                ModelPickers.resolveWith(picker, new Seat(ModelPickersCeilingTest.class, Grade.SMALL, ModelKind.LLM), situation()).getId(),
                "a seat at the ceiling is untouched");
    }

    @Test
    void aPickerReturningLessThanItsCeilingIsStillRefused() {
        ModelPicker picker = new ModelPicker() {
            @Override
            public ModelSpec provide(Seat seat, Situation situation) {
                return TestModels.small();
            }

            @Override
            public Grade ceiling() {
                return Grade.XL;
            }
        };
        UncorrectableRuntimeLLMException ex = assertThrows(UncorrectableRuntimeLLMException.class,
                () -> ModelPickers.resolveWith(picker, new Seat(ModelPickersCeilingTest.class, Grade.MEDIUM, ModelKind.LLM), situation()));
        assertTrue(ex.getMessage().contains("below the declared floor"), ex.getMessage());
    }
}
