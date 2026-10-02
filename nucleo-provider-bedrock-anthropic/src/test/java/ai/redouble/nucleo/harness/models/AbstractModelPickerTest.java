/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.retry.*;
import ai.redouble.nucleo.providers.bedrock.*;
import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The stateful picker's availability policy: what turns a model off (availability
 * failures with no success inside the window), what never does (throttle, auth,
 * exclusion, missing data), how observations accumulate, and how the template refuses
 * with the probe evidence - while a pin resolves regardless, because turnoff is picker
 * policy and pins never consult the picker.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-29)
 */
class AbstractModelPickerTest {

    // One live identity on all three Anthropic routes: the subject of every route-wall test.
    private static final Map<String, ModelSpec> ROUTES = TestModels.tripleRoutedVariants();
    private static final ModelSpec MANTLE = ROUTES.get("anthropic-bedrock-mantle");
    private static final ModelSpec BEDROCK = ROUTES.get("anthropic-bedrock");
    private static final ModelSpec DIRECT = ROUTES.get("anthropic-direct");

    /** A live Mantle spec of a DIFFERENT identity - an overload elsewhere must not move the pick. */
    private static ModelSpec otherMantleIdentity() {
        return Models.all().stream()
                .filter(s -> MANTLE.getProviderKey().equals(s.getProviderKey()))
                .filter(s -> s.getStatus() == ModelStatus.OPEN && !s.getIdentity().equals(MANTLE.getIdentity()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("the Mantle route serves a single identity"));
    }

    private static class PinnedToSmall extends BedrockOnlyModelPicker {
        @Override
        protected ModelSpec pick(Seat seat, Situation situation) {
            return TestModels.small();
        }
    }

    private static ProbeOutcome outcome(String specId, ProbeOutcome.Status status,
                                        ProbeOutcome.Classification classification, Instant at) {
        ProbeOutcome outcome = new ProbeOutcome();
        outcome.setSpecId(specId);
        outcome.setStatus(status);
        outcome.setClassification(classification);
        outcome.setProbedAt(at);
        outcome.setErrorClass("ExternalServiceException");
        outcome.setErrorMessage("connection refused");
        return outcome;
    }

    private static Seat seat() {
        return new Seat(AbstractModelPickerTest.class, Grade.SMALL, ModelKind.LLM);
    }

    @Test
    void coldPickerTurnsNothingOff() {
        assertEquals(TestModels.small().getId(), new PinnedToSmall().provide(seat(), new Situation()).getId());
    }

    @Test
    void availabilityFailureWithNoSuccessInWindowTurnsOff() {
        PinnedToSmall picker = new PinnedToSmall();
        picker.recordProbe(outcome(TestModels.small().getId(), ProbeOutcome.Status.FAILED,
                ProbeOutcome.Classification.AVAILABILITY, Instant.now().minus(Duration.ofHours(1))));
        UncorrectableRuntimeLLMException refusal = assertThrows(UncorrectableRuntimeLLMException.class,
                () -> picker.provide(seat(), new Situation()));
        assertTrue(refusal.getMessage().contains("treated as deprecated"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("connection refused"), "the refusal carries the probe evidence");
    }

    @Test
    void successInsideTheWindowKeepsTheModelOn() {
        PinnedToSmall picker = new PinnedToSmall();
        picker.recordProbe(outcome(TestModels.small().getId(), ProbeOutcome.Status.OK, null,
                Instant.now().minus(Duration.ofHours(3))));
        picker.recordProbe(outcome(TestModels.small().getId(), ProbeOutcome.Status.FAILED,
                ProbeOutcome.Classification.AVAILABILITY, Instant.now().minus(Duration.ofHours(1))));
        assertEquals(TestModels.small().getId(), picker.provide(seat(), new Situation()).getId());
    }

    @Test
    void throttleAuthAndExclusionNeverTurnOff() {
        PinnedToSmall picker = new PinnedToSmall();
        String id = TestModels.small().getId();
        picker.recordProbe(outcome(id, ProbeOutcome.Status.FAILED, ProbeOutcome.Classification.THROTTLE, Instant.now()));
        picker.recordProbe(outcome(id, ProbeOutcome.Status.FAILED, ProbeOutcome.Classification.AUTH, Instant.now()));
        picker.recordProbe(outcome(id, ProbeOutcome.Status.EXCLUDED, null, Instant.now()));
        assertEquals(id, picker.provide(seat(), new Situation()).getId());
    }

    @Test
    void recoveryResetsTheFailureStreak() {
        PinnedToSmall picker = new PinnedToSmall();
        String id = TestModels.small().getId();
        picker.recordProbe(outcome(id, ProbeOutcome.Status.FAILED, ProbeOutcome.Classification.AVAILABILITY,
                Instant.now().minus(Duration.ofHours(2))));
        picker.recordProbe(outcome(id, ProbeOutcome.Status.OK, null, Instant.now()));
        assertEquals(id, picker.provide(seat(), new Situation()).getId());
    }

    @Test
    void staleFailuresOutsideTheWindowDoNotTurnOff() {
        PinnedToSmall picker = new PinnedToSmall();
        picker.recordProbe(outcome(TestModels.small().getId(), ProbeOutcome.Status.FAILED,
                ProbeOutcome.Classification.AVAILABILITY, Instant.now().minus(Duration.ofDays(3))));
        assertEquals(TestModels.small().getId(), picker.provide(seat(), new Situation()).getId());
    }

    @Test
    void recordProbeReturnsTheDisplacedObservation() {
        PinnedToSmall picker = new PinnedToSmall();
        String id = TestModels.small().getId();
        ProbeOutcome first = outcome(id, ProbeOutcome.Status.OK, null, Instant.now().minus(Duration.ofHours(6)));
        assertNull(picker.recordProbe(first), "nothing displaced on the first observation");
        ProbeOutcome second = outcome(id, ProbeOutcome.Status.OK, null, Instant.now());
        assertSame(first, picker.recordProbe(second), "the prior observation comes back for drift");
    }

    @Test
    void aPinResolvesAgainstATurnedOffSpec() {
        // Turnoff is picker policy and pins never consult the picker - deliberately, so
        // probes detect recovery and an explicit pin rides a turned-off model
        PinnedToSmall picker = new PinnedToSmall();
        picker.recordProbe(outcome(TestModels.small().getId(), ProbeOutcome.Status.FAILED,
                ProbeOutcome.Classification.AVAILABILITY, Instant.now()));
        assertThrows(UncorrectableRuntimeLLMException.class, () -> picker.provide(seat(), new Situation()));
        Situation permitting = new Situation();
        permitting.setEnvelope(spec -> true);
        assertEquals(TestModels.small().getId(),
                ModelPickers.resolvePinned(TestModels.small(), seat(), permitting).getId());
    }

    private static class PinnedTo extends BedrockOnlyModelPicker {
        private final ModelSpec pin;

        PinnedTo(ModelSpec pin) {
            this.pin = pin;
        }

        @Override
        protected ModelSpec pick(Seat seat, Situation situation) {
            return pin;
        }
    }

    private static Situation overloadedOn(String... specIds) {
        Situation situation = new Situation();
        situation.setEnvelope(spec -> true);
        List<Situation.Attempt> attempts = new ArrayList<>();
        for (String specId : specIds) {
            attempts.add(new Situation.Attempt(Models.spec(specId),
                    new OverloadRetryException("overloaded", "anthropic", "529", null)));
        }
        situation.setAttempts(attempts);
        return situation;
    }

    @Test
    void anOverloadedRouteFailsOverToItsBedrockSibling() {
        // The mantle route just 529'd: the same identity serves from native bedrock
        PinnedTo picker = new PinnedTo(MANTLE);
        ModelSpec served = picker.provide(seat(), overloadedOn(MANTLE.getId()));
        assertEquals(BEDROCK.getId(), served.getId());
        assertEquals(MANTLE.getIdentity(), served.getIdentity(), "failover never leaves the identity");
    }

    @Test
    void failoverNeverReachesADirectEndpoint() {
        // Both Bedrock routes overloaded: the direct sibling exists and is permitted, but
        // direct endpoints are out of bounds - the pick stands and the dispatcher's
        // backoff continues
        PinnedTo picker = new PinnedTo(MANTLE);
        ModelSpec served = picker.provide(seat(),
                overloadedOn(MANTLE.getId(), BEDROCK.getId()));
        assertEquals(MANTLE.getId(), served.getId());
    }

    @Test
    void anOverloadElsewhereDoesNotTouchThePick() {
        // A different identity 529'd - and separately, a non-overload failure on the pick itself
        PinnedTo picker = new PinnedTo(MANTLE);
        assertEquals(MANTLE.getId(),
                picker.provide(seat(), overloadedOn(otherMantleIdentity().getId())).getId());
        Situation transientFailure = new Situation();
        transientFailure.setEnvelope(spec -> true);
        transientFailure.setAttempts(List.of(new Situation.Attempt(MANTLE,
                new TransientErrorRetryException("boom", "anthropic", "500", 500, 1, null))));
        assertEquals(MANTLE.getId(), picker.provide(seat(), transientFailure).getId());
    }

    @Test
    void failoverRespectsTheEnvelopeAndTheTurnoff() {
        // Envelope refuses the sibling: the pick stands
        PinnedTo picker = new PinnedTo(MANTLE);
        Situation refusingSibling = overloadedOn(MANTLE.getId());
        refusingSibling.setEnvelope(spec -> !BEDROCK.getId().equals(spec.getId()));
        assertEquals(MANTLE.getId(), picker.provide(seat(), refusingSibling).getId());
        // Probe-dead sibling: the pick stands
        PinnedTo probeAware = new PinnedTo(MANTLE);
        probeAware.recordProbe(outcome(BEDROCK.getId(), ProbeOutcome.Status.FAILED,
                ProbeOutcome.Classification.AVAILABILITY, Instant.now()));
        assertEquals(MANTLE.getId(),
                probeAware.provide(seat(), overloadedOn(MANTLE.getId())).getId());
    }

    /** A pin with per-route slowdown factors injected, standing in for the live limiters. */
    private static class ThrottledPin extends BedrockOnlyModelPicker {
        private final ModelSpec pin;
        private final Map<String, Double> slowdowns;

        ThrottledPin(ModelSpec pin, Map<String, Double> slowdowns) {
            this.pin = pin;
            this.slowdowns = slowdowns;
        }

        @Override
        protected ModelSpec pick(Seat seat, Situation situation) {
            return pin;
        }

        @Override
        protected double routeSlowdown(ModelSpec spec) {
            return slowdowns.getOrDefault(spec.getId(), 1.0);
        }
    }

    private static Situation permittingSituation() {
        Situation situation = new Situation();
        situation.setEnvelope(spec -> true);
        return situation;
    }

    @Test
    void theDivertedShareFollowsTheThrottle() {
        assertEquals(0.0, AbstractModelPicker.diversionFraction(1.0));
        assertEquals(0.0, AbstractModelPicker.diversionFraction(1.9));
        assertEquals(0.5, AbstractModelPicker.diversionFraction(2.0));
        assertEquals(2.0 / 3.0, AbstractModelPicker.diversionFraction(3.0), 0.001);
        assertEquals(1.0, AbstractModelPicker.diversionFraction(10.0));
        assertEquals(1.0, AbstractModelPicker.diversionFraction(50.0));
    }

    @Test
    void aFullyThrottledRouteDivertsEveryFreshPickToTheHealthiestSibling() {
        ThrottledPin picker = new ThrottledPin(MANTLE,
                Map.of(MANTLE.getId(), 10.0, BEDROCK.getId(), 1.0));
        assertEquals(BEDROCK.getId(),
                picker.provide(seat(), permittingSituation()).getId());
    }

    @Test
    void aHealthyRouteKeepsItsTraffic() {
        ThrottledPin picker = new ThrottledPin(MANTLE,
                Map.of(MANTLE.getId(), 1.5, BEDROCK.getId(), 1.0));
        assertEquals(MANTLE.getId(),
                picker.provide(seat(), permittingSituation()).getId());
    }

    @Test
    void diversionBetweenTwoDrowningRoutesRelievesNothingSoItDoesNotHappen() {
        // The sibling must be strictly less throttled - and direct endpoints are never
        // candidates, so an equally drowning Bedrock sibling means the pick stands
        ThrottledPin picker = new ThrottledPin(MANTLE,
                Map.of(MANTLE.getId(), 10.0, BEDROCK.getId(), 10.0,
                       DIRECT.getId(), 1.0));
        assertEquals(MANTLE.getId(),
                picker.provide(seat(), permittingSituation()).getId());
    }

    @Test
    void aZdrOnlyPickerVetoesItsOwnNonZdrPicks() {
        // A data-share-only spec: a ZDR family refuses its own pick
        ZdrOnlyModelPicker laxPin = new ZdrOnlyModelPicker() {
            @Override
            protected ModelSpec pick(Seat seat, Situation situation) {
                return TestModels.requiringLax();
            }
        };
        UncorrectableRuntimeLLMException refusal = assertThrows(UncorrectableRuntimeLLMException.class,
                () -> laxPin.provide(seat(), permittingSituation()));
        assertTrue(refusal.getMessage().contains("ZDR-only"), refusal.getMessage());
        // A non-Bedrock route fails the same vet with the route named
        ZdrOnlyModelPicker directPin = new ZdrOnlyModelPicker() {
            @Override
            protected ModelSpec pick(Seat seat, Situation situation) {
                return DIRECT;
            }
        };
        assertThrows(UncorrectableRuntimeLLMException.class,
                () -> directPin.provide(seat(), permittingSituation()));
        // The ZDR mainstream passes untouched
        ZdrOnlyModelPicker zdrPin = new ZdrOnlyModelPicker() {
            @Override
            protected ModelSpec pick(Seat seat, Situation situation) {
                return BEDROCK;
            }
        };
        assertEquals(BEDROCK.getId(), zdrPin.provide(seat(), permittingSituation()).getId());
    }

    @Test
    void anOpenPickerMayFailOverToADirectEndpoint() {
        // Both Bedrock routes overloaded: the open family reaches the direct sibling
        // that the Bedrock-only family refuses
        OpenModelPicker open = new OpenModelPicker() {
            @Override
            protected ModelSpec pick(Seat seat, Situation situation) {
                return MANTLE;
            }
        };
        ModelSpec served = open.provide(seat(),
                overloadedOn(MANTLE.getId(), BEDROCK.getId()));
        assertEquals(DIRECT.getId(), served.getId());
    }

    @Test
    void statelessPickersIgnoreObservations() {
        ModelPicker lambda = (seat, situation) -> TestModels.small();
        assertNull(lambda.recordProbe(outcome(TestModels.small().getId(), ProbeOutcome.Status.OK, null, Instant.now())));
        assertTrue(lambda.describePins().isEmpty());
    }
}
