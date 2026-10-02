/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.models.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The conversation's seat declaration: grade/depth/interactive and the resolve-stamped
 * prior survive the persistence snapshot, the restored conversation comes back UNBOUND
 * (a fresh binding is a new attempt's business), and vision demand derives from the
 * payload.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-28)
 */
class ConversationDeclarationTest {

    private static ConversationContext declaredAndResolved() {
        ConversationContext conversation = new ConversationContext();
        conversation.setGrade(Grade.MEDIUM);
        conversation.setDepth(Depth.THOROUGH);
        conversation.setInteractive(true);
        ModelBinding binding = new ModelBinding(Grade.MEDIUM, Depth.THOROUGH);
        conversation.setModelBinding(binding);
        binding.resolve(TestModels.small());
        return conversation;
    }

    @Test
    void declarationAndPriorSurviveTheSnapshotRoundTrip() {
        ConversationContext restored = declaredAndResolved().toSnapshot().toConversation(StringResponseHandler.instance);
        assertEquals(Grade.MEDIUM, restored.getGrade());
        assertEquals(Depth.THOROUGH, restored.getDepth());
        assertTrue(restored.isInteractive());
        assertEquals(TestModels.small().getId(), restored.getPriorSpecId(), "the serving spec comes back as the prior");
        assertFalse(restored.isModelResolved(), "restored conversations are unbound until a job wires a fresh binding");
        assertThrows(UncorrectableRuntimeLLMException.class, restored::getModel);
    }

    @Test
    void singleTurnIsATaskCall_boundImmediateAndCapped() {
        ConversationContext conversation = ConversationContext.singleTurn(TestModels.small(), "extract the number", 900);
        assertTrue(conversation.isModelResolved(), "the model is given, not resolved later - the binding arrives pre-resolved");
        assertEquals(TestModels.small().getId(), conversation.getModel().getId());
        assertEquals(Depth.IMMEDIATE, conversation.resolveDepth(), "a precise extraction thinks at IMMEDIATE - a reasoning budget sends small models into counting spirals");
        assertEquals(1, conversation.getMessages().size(), "one user turn carrying the prompt");
        assertEquals("user", conversation.getMessages().get(0).getRole());
        assertTrue(conversation.getMessages().get(0).getRawContent().contains("extract the number"));
        assertEquals(900, conversation.resolveOutputBudget(), "the backstop rides the message as its per-call output cap");
    }

    /** An image block is carried as images and a file block as documents, each on its own, anywhere in the history. */
    @Test
    void theCarriedInputsDeriveFromThePayloadBlockByBlock() throws Exception {
        ConversationContext textOnly = new ConversationContext();
        OutgoingMessage<String> text = new OutgoingMessage<>(StringResponseHandler.instance);
        text.setRole("user");
        text.addText("plain words");
        textOnly.getMessages().add(text);
        assertEquals(Set.of(), textOnly.carriedInputs());
        ConversationContext withImage = new ConversationContext();
        OutgoingMessage<String> imageMessage = new OutgoingMessage<>(StringResponseHandler.instance);
        imageMessage.setRole("user");
        imageMessage.addImage(new ByteArrayInputStream(new byte[]{1, 2, 3}), "image/png", "probe");
        withImage.getMessages().add(imageMessage);
        assertEquals(Set.of(Input.IMAGES), withImage.carriedInputs(), "an image is images, never documents");
        OutgoingMessage<String> fileMessage = new OutgoingMessage<>(StringResponseHandler.instance);
        fileMessage.setRole("user");
        fileMessage.addFile(new ByteArrayInputStream("%PDF-1.4".getBytes()), "application/pdf", "probe.pdf");
        withImage.getMessages().add(fileMessage);
        assertEquals(Set.of(Input.IMAGES, Input.DOCUMENTS), withImage.carriedInputs(), "a file is documents, and the image before it still counts");
    }
}
