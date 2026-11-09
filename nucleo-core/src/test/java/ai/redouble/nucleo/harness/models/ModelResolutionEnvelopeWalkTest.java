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
 * An envelope refusal of an unpinned seat is not terminal: the seat is served from the
 * nearest rung whose answer the envelope permits. A CEILING seat walks downward from MEGA
 * (it asked for the best this application may call); an explicit-grade seat walks upward
 * from its own rung, then downward. The walk is for a picker of the deployment's own, here
 * one that answers with the grade's pinned default whatever the envelope says; the shipped
 * picker passes over a refused entry itself. A pin stays
 * terminal, and a walk the envelope refuses whole raises {@link ModelResolutionError} - an
 * {@link Error}, because a deployment whose policy permits no model for a seat is broken,
 * not correctable.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-20)
 */
class ModelResolutionEnvelopeWalkTest {

    @AfterEach
    void reset() {
        ModelPickers.resetForTests();
    }

    private static Situation refusing(ComplianceEnvelope envelope) {
        Situation situation = new Situation();
        situation.setEnvelope(envelope);
        return situation;
    }

    /** A picker of the deployment's own: the grade's pinned default whatever the envelope says, so the gate's walk has work to do. */
    private static ModelPicker blind() {
        return (seat, situation) -> TestModels.grade(seat.grade() == Grade.CEILING ? Grade.MEGA : seat.grade());
    }

    @Test
    void aCeilingSeatRefusedAtTheTopIsServedFromTheBestPermittedRung() {
        // The production shape: the ceiling entry requires data sharing, the application's
        // envelope does not permit it - the chat seat rides the best permitted rung below
        ModelSpec served = ModelPickers.resolveWith(blind(),
                new Seat(ModelResolutionEnvelopeWalkTest.class, Grade.CEILING, ModelKind.LLM),
                refusing(spec -> !spec.requiresLax()));
        assertEquals("claude-opus-5-mantle", served.getId(),
                "the best entry the envelope permits serves the best-available seat");
    }

    @Test
    void anExplicitSeatRefusedAtItsRungIsServedFromAbove() {
        ModelSpec served = ModelPickers.resolveWith(blind(),
                new Seat(ModelResolutionEnvelopeWalkTest.class, Grade.LARGE, ModelKind.LLM),
                refusing(spec -> !"gpt-5".equals(spec.getId())));
        assertEquals("claude-opus-5-mantle", served.getId(), "the refused rung is served from the rung above first");
    }

    @Test
    void aSeatRefusedAtItsRungAndAboveIsServedFromBelow() {
        // The one MEGA entry requires data sharing and nothing is above MEGA: the seat runs
        // on the XL entry, below the grade it declared, rather than not at all
        ModelSpec served = ModelPickers.resolveWith(blind(),
                new Seat(ModelResolutionEnvelopeWalkTest.class, Grade.MEGA, ModelKind.LLM),
                refusing(spec -> !spec.requiresLax()));
        assertEquals("claude-opus-5-mantle", served.getId(), "the nearest rung below whose answer the envelope permits");
    }

    @Test
    void anEnvelopeRefusingEverythingIsTheBrokenDeploymentError() {
        ModelResolutionError refusal = assertThrows(ModelResolutionError.class,
                () -> ModelPickers.resolveWith(blind(),
                        new Seat(ModelResolutionEnvelopeWalkTest.class, Grade.CEILING, ModelKind.LLM),
                        refusing(spec -> false)));
        assertInstanceOf(Error.class, refusal,
                "a deployment that can serve no model at all fails as an Error, past every catch (Exception)");
        assertTrue(refusal.getMessage().contains("claude-fable-5-mantle") && refusal.getMessage().contains("nova-micro"),
                "the refusal names every entry the walk was refused: " + refusal.getMessage());
    }

    @Test
    void aPinnedSpecTheEnvelopeRefusesStaysRefused() {
        UncorrectableRuntimeLLMException refusal = assertThrows(UncorrectableRuntimeLLMException.class,
                () -> ModelPickers.resolvePinned(Models.spec("claude-fable-5-mantle"),
                        new Seat(ModelResolutionEnvelopeWalkTest.class, null, ModelKind.LLM),
                        refusing(spec -> !spec.requiresLax())));
        assertTrue(refusal.getMessage().contains("refuses claude-fable-5-mantle"),
                "the job named one exact entry, so there is no walk: " + refusal.getMessage());
    }
}
