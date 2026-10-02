/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The binding cell's contract: write-once resolution, loud pre-resolution reads, the
 * three pricing forms, and the prior stamp that makes stickiness survive restarts.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-28)
 */
class ModelBindingTest {

    @Test
    void resolveIsWriteOnce() {
        ModelBinding binding = new ModelBinding(Grade.SMALL, Depth.STANDARD, 100, OutputDeclaration.of(50));
        binding.resolve(TestModels.small());
        assertThrows(IllegalStateException.class, () -> binding.resolve(TestModels.micro()));
    }

    @Test
    void preResolutionGetModelThrows() {
        ModelBinding binding = new ModelBinding(Grade.SMALL, Depth.STANDARD, 100, OutputDeclaration.of(50));
        assertThrows(UncorrectableRuntimeLLMException.class, binding::getModel);
    }

    @Test
    void unpricedReservationThrows() {
        ModelBinding binding = new ModelBinding(Grade.SMALL, Depth.STANDARD, 100, OutputDeclaration.of(50));
        binding.resolve(TestModels.small());
        assertThrows(IllegalStateException.class, binding::getReservation);
    }

    @Test
    void flatAndPromptFormsRequireTheirDeclarations() {
        // the input count is an int: the type refuses a missing one at compile time
        assertThrows(IllegalArgumentException.class,
                () -> new ModelBinding(Grade.SMALL, Depth.STANDARD, 100, (OutputDeclaration) null),
                "no form has a default output");
        assertThrows(IllegalArgumentException.class,
                () -> new ModelBinding(Grade.SMALL, Depth.STANDARD, "prompt", (OutputDeclaration) null),
                "the prompt form declares its output too");
    }

    @Test
    void llmFormsRequireGradeAndDepth() {
        assertThrows(IllegalArgumentException.class, () -> new ModelBinding((Grade) null, Depth.STANDARD));
        assertThrows(IllegalArgumentException.class, () -> new ModelBinding(Grade.SMALL, null));
    }

    @Test
    void flatPricingIsInputPlusDeclaredOutputPlusTheDepthsThinking() {
        ModelSpec small = TestModels.small();
        ModelBinding noThinking = new ModelBinding(Grade.SMALL, Depth.IMMEDIATE, 100, OutputDeclaration.of(200));
        noThinking.resolve(small);
        assertEquals(300, noThinking.price(), "IMMEDIATE books no reasoning on an Anthropic mode");
        ModelBinding thinking = new ModelBinding(Grade.SMALL, Depth.STANDARD, 100, OutputDeclaration.of(200));
        thinking.resolve(small);
        assertEquals(100 + ConversationContext.outputReserve(small, 200, Depth.STANDARD), thinking.price(),
                "STANDARD books the spec's thinking budget above the declared output, as the wire will");
        ModelBinding rung = new ModelBinding(Grade.SMALL, Depth.IMMEDIATE, 100, OutputDeclaration.of(OutputSize.COMPACT));
        rung.resolve(small);
        assertEquals(100 + small.getOutputBudget(OutputSize.COMPACT), rung.price(),
                "a rung is translated by the resolved entry");
    }

    @Test
    void promptPricingCountsUnderResolvedSpecPlusDeclaredOutput() {
        String prompt = "Summarize the quarterly numbers in one sentence.";
        ModelBinding binding = new ModelBinding(Grade.SMALL, Depth.IMMEDIATE, prompt, OutputDeclaration.of(500));
        binding.resolve(TestModels.small());
        int counted = TokenizerFactory.get().forModel(TestModels.small()).countTokens(prompt);
        assertEquals(counted + 500, binding.price());
    }

    @Test
    void wiredPricingMatchesTheConversationFormula() {
        ConversationContext conversation = new ConversationContext();
        conversation.setOutputDeclaration(OutputDeclaration.of(OutputSize.COMPACT));
        ModelBinding binding = new ModelBinding(Grade.SMALL, Depth.STANDARD);
        conversation.setModelBinding(binding);
        OutgoingMessage<String> message = new OutgoingMessage<>(StringResponseHandler.instance);
        message.setRole("user");
        message.addText("What is the capital of France?");
        conversation.getMessages().add(message);
        binding.resolve(TestModels.small());
        int expected = conversation.getTotalTokens(TestModels.small())
                + conversation.resolveOutputBudget() + conversation.resolveThinkingBudget();
        assertEquals(expected, binding.price());
    }

    @Test
    void resolveStampsThePriorOnTheWiredConversation() {
        ConversationContext conversation = new ConversationContext();
        ModelBinding binding = new ModelBinding(Grade.SMALL, Depth.STANDARD);
        conversation.setModelBinding(binding);
        assertNull(conversation.getPriorSpecId());
        binding.resolve(TestModels.small());
        assertEquals(TestModels.small().getId(), conversation.getPriorSpecId());
    }

    @Test
    void wiredInputExceedingTheContextWindowFailsAtPricing() {
        StandardModelSpec tiny = new StandardModelSpec();
        tiny.setId("tiny-window-fake");
        tiny.setIdentity("tiny-window");
        tiny.setProviderKey("openai");
        tiny.setWireModelId("tiny");
        tiny.setMaxContextTokens(5);
        tiny.setMaxOutputTokens(100);
        tiny.setTpm(1000);
        ConversationContext conversation = new ConversationContext();
        conversation.setOutputDeclaration(OutputDeclaration.of(1));
        ModelBinding binding = new ModelBinding(Grade.SMALL, Depth.IMMEDIATE);
        conversation.setModelBinding(binding);
        OutgoingMessage<String> message = new OutgoingMessage<>(StringResponseHandler.instance);
        message.setRole("user");
        message.addText("a payload comfortably larger than a five token context window, by any tokenizer");
        conversation.getMessages().add(message);
        binding.resolve(tiny);
        UncorrectableRuntimeLLMException overflow = assertThrows(UncorrectableRuntimeLLMException.class, binding::price);
        assertTrue(overflow.getMessage().contains("context window"), overflow.getMessage());
    }

    @Test
    void pinnedFormSetsTheKindAndValidatesTheSpecAtMint() {
        // The declaration form decides the kind; a wrong-kind spec is a mint-time error
        assertThrows(IllegalArgumentException.class,
                () -> new ModelBinding(TestModels.embeddings(), Depth.STANDARD, 10, OutputDeclaration.of(10)));
        assertThrows(IllegalArgumentException.class,
                () -> ModelBinding.embeddingsPinned(TestModels.small(), 10));
        assertThrows(IllegalArgumentException.class, () -> new ModelBinding((ModelSpec) null, Depth.STANDARD));
        assertThrows(IllegalArgumentException.class, () -> new ModelBinding(TestModels.small(), null));
        ModelBinding pinned = new ModelBinding(TestModels.small(), Depth.IMMEDIATE, 64, OutputDeclaration.of(16));
        assertTrue(pinned.isPinned());
        assertFalse(pinned.isEmbeddings());
        assertEquals(TestModels.small().getId(), pinned.getPinnedSpec().getId());
        assertFalse(pinned.isResolved(), "a pin is a declaration - the gate still resolves it");
        pinned.resolve(TestModels.small());
        assertEquals(80, pinned.price());
        ModelBinding embeddingsPin = ModelBinding.embeddingsPinned(TestModels.embeddings(), 16);
        assertTrue(embeddingsPin.isPinned());
        assertTrue(embeddingsPin.isEmbeddings());
    }

    @Test
    void embeddingsBindingHasNoGrade() {
        ModelBinding binding = ModelBinding.embeddings(500);
        assertTrue(binding.isEmbeddings());
        assertNull(binding.getGrade());
        binding.resolve(TestModels.embeddings());
        assertEquals(500, binding.price());
    }
}
