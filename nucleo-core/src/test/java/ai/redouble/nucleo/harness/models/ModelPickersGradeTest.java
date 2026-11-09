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
 * The grade is the picker's to honour, never the gate's to enforce: the gate hands the picker
 * the seat as declared, {@code CEILING} included and a grade above everything the picker
 * reports serving included, and serves whatever grade the picker answers with. The
 * picker's reported ceiling is for reporting alone, and must name a rung.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class ModelPickersGradeTest {

    @AfterEach
    void reset() {
        ModelPickers.resetForTests();
    }

    private static Situation situation() {
        Situation situation = new Situation();
        situation.setEnvelope(spec -> true);
        return situation;
    }

    private static Seat seat(Grade grade) {
        return new Seat(ModelPickersGradeTest.class, grade, ModelKind.LLM);
    }

    /** A picker that records the seat it was handed and answers with one spec, reporting one grade as its ceiling. */
    private static final class Recording implements ModelPicker {
        Grade handed;
        final ModelSpec answer;
        final Grade ceiling;

        Recording(ModelSpec answer, Grade ceiling) {
            this.answer = answer;
            this.ceiling = ceiling;
        }

        @Override
        public ModelSpec provide(Seat seat, Situation situation) {
            handed = seat.grade();
            return answer;
        }

        @Override
        public Grade ceiling() {
            return ceiling;
        }
    }

    @Test
    void theSeatReachesThePickerAsDeclared() {
        Recording picker = new Recording(TestModels.small(), Grade.SMALL);
        ModelPickers.resolveWith(picker, seat(Grade.MEGA), situation());
        assertEquals(Grade.MEGA, picker.handed, "a seat above everything the picker reports serving is not lowered for it");
        ModelPickers.resolveWith(picker, seat(Grade.CEILING), situation());
        assertEquals(Grade.CEILING, picker.handed, "CEILING is the picker's to serve, from MEGA downward");
    }

    @Test
    void anAnswerBelowTheSeatsGradeIsServed() {
        ModelSpec served = ModelPickers.resolveWith(new Recording(TestModels.small(), Grade.XL), seat(Grade.MEDIUM), situation());
        assertEquals(TestModels.small().getId(), served.getId(),
                "the picker chose below the grade and said why in its own log; the gate has no floor to refuse it at");
    }

    @Test
    void theReportedCeilingIsThePickersWordAndMustNameARung() {
        ModelPickers.use(new Recording(TestModels.small(), Grade.CEILING));
        UncorrectableRuntimeLLMException ex = assertThrows(UncorrectableRuntimeLLMException.class, ModelPickers::ceiling);
        assertTrue(ex.getMessage().contains("names a rung"), ex.getMessage());
    }
}
