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
 * The embeddings channel's contract: the model is a corpus configuration constant -
 * declared once, gated, frozen for the process - because stored vectors are only
 * comparable to vectors from the model that produced them. A deployment that declares
 * none refuses; a declaration that changes mid-process refuses instead of corrupting
 * every similarity search; the envelope still judges the declaration.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-29)
 */
class EmbeddingsResolutionTest {

    private static Seat embeddingsSeat() {
        return new Seat(EmbeddingsResolutionTest.class, null, ModelKind.EMBEDDINGS);
    }

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
    void theDeclarationResolvesFreezesAndRefusesAMidProcessChange() {
        ModelPicker declaring = new TestModelPicker();
        ModelSpec first = ModelPickers.resolveEmbeddingsWith(declaring, embeddingsSeat(), permitting());
        assertEquals(TestModels.embeddings().getId(), first.getId());
        // The same declaration resolves freely forever
        assertEquals(first.getId(),
                ModelPickers.resolveEmbeddingsWith(declaring, embeddingsSeat(), permitting()).getId());
        // A different declaration mid-process is the corruption the freeze exists to stop
        ModelPicker swapped = new TestModelPicker() {
            @Override
            public ModelSpec embeddingsSpec() {
                return TestModels.otherEmbeddings();
            }
        };
        UncorrectableRuntimeLLMException refusal = assertThrows(UncorrectableRuntimeLLMException.class,
                () -> ModelPickers.resolveEmbeddingsWith(swapped, embeddingsSeat(), permitting()));
        assertTrue(refusal.getMessage().contains(first.getId()), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("corpus migration"), refusal.getMessage());
    }

    @Test
    void aDeploymentWithoutADeclarationRefuses() {
        ModelPicker undeclared = (seat, situation) -> TestModels.small();
        UncorrectableRuntimeLLMException refusal = assertThrows(UncorrectableRuntimeLLMException.class,
                () -> ModelPickers.resolveEmbeddingsWith(undeclared, embeddingsSeat(), permitting()));
        assertTrue(refusal.getMessage().contains("no embeddings spec"), refusal.getMessage());
    }

    @Test
    void aNonEmbeddingsDeclarationDiesAtTheKindWall() {
        ModelPicker wrongKind = new TestModelPicker() {
            @Override
            public ModelSpec embeddingsSpec() {
                return TestModels.small();
            }
        };
        assertThrows(UncorrectableRuntimeLLMException.class,
                () -> ModelPickers.resolveEmbeddingsWith(wrongKind, embeddingsSeat(), permitting()));
    }

    @Test
    void theEnvelopeStillJudgesTheDeclaration() {
        Situation refusing = new Situation();
        refusing.setEnvelope(spec -> false);
        assertThrows(UncorrectableRuntimeLLMException.class,
                () -> ModelPickers.resolveEmbeddingsWith(new TestModelPicker(), embeddingsSeat(), refusing));
    }
}
