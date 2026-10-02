/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.providers.bedrock.anthropic.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The gate in front of the picker, every wall: envelope, kind, grade floor, inputs (declared
 * and accepted, carried and declared), deprecation - plus the Mantle client's last-line workspace decision and the stock
 * envelope's knobs. Each refusal must name its reason, never NPE.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-28)
 */
class ModelResolutionGateTest {

    private static Situation permitting() {
        Situation situation = new Situation();
        situation.setEnvelope(spec -> true);
        return situation;
    }

    private static Seat llmSeat(Grade grade) {
        return new Seat(ModelResolutionGateTest.class, grade, ModelKind.LLM);
    }

    @Test
    void happyPathReturnsThePickedSpec() {
        ModelSpec picked = ModelPickers.resolveWith(
                (seat, situation) -> TestModels.small(), llmSeat(Grade.SMALL), permitting());
        assertEquals(TestModels.small().getId(), picked.getId());
    }

    @Test
    void missingEnvelopeIsRefused() {
        assertThrows(UncorrectableRuntimeLLMException.class,
                () -> ModelPickers.resolveWith((seat, situation) -> TestModels.small(), llmSeat(Grade.SMALL), new Situation()));
    }

    @Test
    void envelopeRefusingEveryRungNamesTheEnvelopeAndIsTheBrokenDeploymentError() {
        // A picker yielding only the refused entry leaves the walk nowhere to go: the
        // exhausted walk is ModelResolutionError, naming the envelope and the entry
        Situation refusing = new Situation();
        refusing.setEnvelope(new DefaultComplianceEnvelope());
        ModelSpec dataShare = TestModels.requiringLax();
        ModelResolutionError refusal = assertThrows(ModelResolutionError.class,
                () -> ModelPickers.resolveWith((seat, situation) -> dataShare,
                        llmSeat(dataShare.getGrade()), refusing));
        assertTrue(refusal.getMessage().contains("DefaultComplianceEnvelope"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains(dataShare.getId()), refusal.getMessage());
    }

    @Test
    void underQualificationIsRefusedOverQualificationIsLegal() {
        assertThrows(UncorrectableRuntimeLLMException.class,
                () -> ModelPickers.resolveWith((seat, situation) -> TestModels.small(), llmSeat(Grade.XL), permitting()));
        ModelSpec bigger = TestModels.grade(Grade.XL);
        ModelSpec overQualified = ModelPickers.resolveWith(
                (seat, situation) -> bigger, llmSeat(Grade.SMALL), permitting());
        assertEquals(bigger.getId(), overQualified.getId());
    }

    @Test
    void kindMismatchIsRefused() {
        assertThrows(UncorrectableRuntimeLLMException.class,
                () -> ModelPickers.resolveWith((seat, situation) -> TestModels.embeddings(),
                        llmSeat(Grade.SMALL), permitting()));
    }

    @Test
    void embeddingsSeatsNeverReachThePicker() {
        // The embeddings model is corpus configuration - the pick channel refuses the kind outright
        UncorrectableRuntimeLLMException refusal = assertThrows(UncorrectableRuntimeLLMException.class,
                () -> ModelPickers.resolveWith((seat, situation) -> TestModels.small(),
                        new Seat(ModelResolutionGateTest.class, null, ModelKind.EMBEDDINGS), permitting()));
        assertTrue(refusal.getMessage().contains("corpus configuration"), refusal.getMessage());
    }

    /** A live graded LLM entry that accepts the input, or does not, as its catalog record says. */
    private static ModelSpec accepting(Input input, boolean accepts) {
        return Models.all().stream()
                .filter(spec -> spec.kind() == ModelKind.LLM && spec.getGrade() != null && spec.getStatus() == ModelStatus.OPEN)
                .filter(spec -> spec.accepts(input) == accepts)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("the shipped catalog has no live entry that " + (accepts ? "accepts " : "refuses ") + input));
    }

    @Test
    void aDeclaredInputTheEntryDoesNotAcceptIsRefusedByName() {
        for (Input input : Input.values()) {
            Situation sending = permitting();
            sending.setSends(Set.of(input));
            ModelSpec blind = accepting(input, false);
            UncorrectableRuntimeLLMException refusal = assertThrows(UncorrectableRuntimeLLMException.class,
                    () -> ModelPickers.resolveWith((seat, situation) -> blind, llmSeat(blind.getGrade()), sending));
            assertTrue(refusal.getMessage().contains(input == Input.IMAGES ? "supports_vision" : "supports_documents"), refusal.getMessage());
            ModelSpec reading = accepting(input, true);
            assertEquals(reading.getId(), ModelPickers.resolveWith((seat, situation) -> reading, llmSeat(reading.getGrade()), sending).getId(),
                    "an entry that accepts the declared input passes");
        }
    }

    @Test
    void aCarriedInputTheRequestDidNotDeclareIsRefusedWhateverTheEntryAccepts() {
        ModelSpec seeing = accepting(Input.IMAGES, true);
        Situation undeclared = permitting();
        undeclared.setCarried(Set.of(Input.IMAGES));
        UncorrectableRuntimeLLMException refusal = assertThrows(UncorrectableRuntimeLLMException.class,
                () -> ModelPickers.resolveWith((seat, situation) -> seeing, llmSeat(seeing.getGrade()), undeclared));
        assertTrue(refusal.getMessage().contains("did not declare"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("ModelBinding.setSends"), refusal.getMessage());
        Situation declared = permitting();
        declared.setSends(Set.of(Input.IMAGES));
        declared.setCarried(Set.of(Input.IMAGES));
        assertEquals(seeing.getId(), ModelPickers.resolveWith((seat, situation) -> seeing, llmSeat(seeing.getGrade()), declared).getId(),
                "declared and carried, on an entry that accepts it: served");
    }

    @Test
    void deprecatedSpecsAreRefused() {
        ModelSpec retired = TestModels.deprecatedLlm();
        assertThrows(UncorrectableRuntimeLLMException.class,
                () -> ModelPickers.resolveWith((seat, situation) -> retired,
                        llmSeat(retired.getGrade()), permitting()));
    }

    @Test
    void pinnedSpecPassesTheGateWithoutAGradeFloor() {
        // A MICRO-grade pin resolves even though a grade seat this low would never be
        // asked for it - a pin declares no floor
        ModelSpec pinned = ModelPickers.resolvePinned(TestModels.micro(),
                new Seat(ModelResolutionGateTest.class, TestModels.micro().getGrade(), ModelKind.LLM), permitting());
        assertEquals(TestModels.micro().getId(), pinned.getId());
    }

    @Test
    void pinnedSpecStillDiesAtEveryGateWall() {
        // Envelope
        Situation refusing = new Situation();
        refusing.setEnvelope(new DefaultComplianceEnvelope());
        ModelSpec dataShare = TestModels.requiringLax();
        assertThrows(UncorrectableRuntimeLLMException.class,
                () -> ModelPickers.resolvePinned(dataShare, llmSeat(dataShare.getGrade()), refusing));
        // Missing envelope
        assertThrows(UncorrectableRuntimeLLMException.class,
                () -> ModelPickers.resolvePinned(TestModels.small(), llmSeat(Grade.SMALL), new Situation()));
        // Kind
        assertThrows(UncorrectableRuntimeLLMException.class,
                () -> ModelPickers.resolvePinned(TestModels.embeddings(), llmSeat(Grade.SMALL), permitting()));
        // Inputs
        Situation images = permitting();
        images.setSends(Set.of(Input.IMAGES));
        ModelSpec blind = accepting(Input.IMAGES, false);
        assertThrows(UncorrectableRuntimeLLMException.class,
                () -> ModelPickers.resolvePinned(blind, llmSeat(blind.getGrade()), images));
        // Deprecation - pinning a model we moved past is a mistake surfaced, not honored
        ModelSpec retired = TestModels.deprecatedLlm();
        assertThrows(UncorrectableRuntimeLLMException.class,
                () -> ModelPickers.resolvePinned(retired, llmSeat(retired.getGrade()), permitting()));
    }

    @Test
    void defaultEnvelopeKnobs() {
        DefaultComplianceEnvelope envelope = new DefaultComplianceEnvelope();
        ModelSpec dataShare = TestModels.requiringLax();
        assertFalse(envelope.permits(dataShare), "data share refused by default");
        assertTrue(envelope.permits(TestModels.small()));
        envelope.setAllowDataShare(true);
        assertTrue(envelope.permits(dataShare));
        java.util.Map<String, ModelSpec> routes = TestModels.tripleRoutedVariants();
        ModelSpec direct = routes.get("anthropic-direct");
        ModelSpec bedrock = routes.get("anthropic-bedrock");
        ModelSpec mantle = routes.get("anthropic-bedrock-mantle");
        envelope.setDeniedProviderKeys(java.util.Set.of(direct.getProviderKey()));
        assertFalse(envelope.permits(direct));
        envelope.setAllowedProviderKeys(java.util.Set.of(mantle.getProviderKey()));
        assertFalse(envelope.permits(bedrock));
        assertTrue(envelope.permits(mantle));
        envelope.setDeniedIdentities(java.util.Set.of(mantle.getIdentity()));
        assertFalse(envelope.permits(mantle));
    }

    @Test
    void mantleWorkspaceDecisionBothBranches() {
        ModelSpec fable = TestModels.requiringLax();
        // Refusing envelope: nothing leaves the process
        assertThrows(UncorrectableRuntimeLLMException.class,
                () -> AnthropicBedrockMantleSDKClient.resolveWorkspace(fable, new DefaultComplianceEnvelope(), "lax-p", "strict-p"));
        // Permitting envelope without a provisioned LAX project: refused with the provisioning hint
        DefaultComplianceEnvelope permitting = new DefaultComplianceEnvelope();
        permitting.setAllowDataShare(true);
        assertThrows(UncorrectableRuntimeLLMException.class,
                () -> AnthropicBedrockMantleSDKClient.resolveWorkspace(fable, permitting, null, "strict-p"));
        // Permitting + provisioned: the LAX project; ordinary models pin to strict
        assertEquals("lax-p", AnthropicBedrockMantleSDKClient.resolveWorkspace(fable, permitting, "lax-p", "strict-p"));
        assertEquals("strict-p", AnthropicBedrockMantleSDKClient.resolveWorkspace(TestModels.small(), permitting, "lax-p", "strict-p"));
    }
}
