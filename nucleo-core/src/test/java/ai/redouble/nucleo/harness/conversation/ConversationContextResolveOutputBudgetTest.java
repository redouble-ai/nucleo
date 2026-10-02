/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.models.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the output-budget resolution chain on a conversation: the last outgoing message's
 * per-call override, else the conversation's own declaration (a rung or a count), else the
 * wired binding's declaration, else a refusal - and every result capped by the model's own
 * output ceiling. There is no framework default anywhere in the chain; a seat that never
 * said how much it answers is a bug the chain surfaces, never a number it invents.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-15)
 */
public class ConversationContextResolveOutputBudgetTest {

    private static OutgoingMessage<String> message(String text) {
        OutgoingMessage<String> msg = new OutgoingMessage<>(StringResponseHandler.instance);
        msg.addText(text);
        return msg;
    }

    @Test
    void nothingDeclared_refuses() {
        ConversationContext context = TestModels.conversation(TestModels.small());
        context.getMessages().add(message("hello"));

        UncorrectableRuntimeLLMException refusal = assertThrows(UncorrectableRuntimeLLMException.class, context::resolveOutputBudget);
        assertTrue(refusal.getMessage().contains("declares no output size"), refusal.getMessage());
    }

    @Test
    void nothingDeclared_depthRefusesToo() {
        ConversationContext context = TestModels.conversation(TestModels.small());

        assertThrows(UncorrectableRuntimeLLMException.class, context::resolveDepth,
                "the seat never said how hard to think either");
    }

    @Test
    void conversationRung_translatesThroughTheModel() {
        ModelSpec small = TestModels.small();
        ConversationContext context = TestModels.conversation(small);
        context.setOutputDeclaration(OutputDeclaration.of(OutputSize.COMPACT));
        context.getMessages().add(message("hello"));

        assertEquals(small.getOutputBudget(OutputSize.COMPACT), context.resolveOutputBudget(),
                "a rung means what the resolved entry says it means");
    }

    @Test
    void conversationCount_isTheCount() {
        ConversationContext context = TestModels.conversation(TestModels.small());
        context.setOutputDeclaration(OutputDeclaration.of(9_000));

        assertEquals(9_000, context.resolveOutputBudget(), "no messages yet: the seat's declaration answers");
    }

    @Test
    void wiredBindingDeclaration_answersWhenTheConversationDoesNot() {
        ModelSpec small = TestModels.small();
        ConversationContext context = new ConversationContext();
        ModelBinding binding = new ModelBinding(Grade.SMALL, Depth.IMMEDIATE, 100, OutputDeclaration.of(OutputSize.VERDICT));
        binding.resolve(small);
        context.setModelBinding(binding);

        assertEquals(small.getOutputBudget(OutputSize.VERDICT), context.resolveOutputBudget(),
                "a job that attaches its priced binding declared the output there");
        assertEquals(Depth.IMMEDIATE, context.resolveDepth(), "and the depth");
    }

    @Test
    void lastOutgoingMessageWithRequestedTokens_overridesTheDeclaration() {
        ConversationContext context = TestModels.conversation(TestModels.small());
        context.setOutputDeclaration(OutputDeclaration.of(OutputSize.COMPACT));
        OutgoingMessage<String> msg = message("hello");
        msg.setRequestedOutputTokens(32_000);
        context.getMessages().add(msg);

        assertEquals(32_000, context.resolveOutputBudget(), "the per-call override wins over the seat's declaration");
    }

    @Test
    void onlyLastOutgoingMessageContributes_earlierOverridesIgnored() {
        ConversationContext context = TestModels.conversation(TestModels.small());
        context.setOutputDeclaration(OutputDeclaration.of(OutputSize.COMPACT));
        OutgoingMessage<String> first = message("first");
        first.setRequestedOutputTokens(4_000);
        context.getMessages().add(first);
        OutgoingMessage<String> second = message("second");
        second.setRequestedOutputTokens(64_000);
        context.getMessages().add(second);

        assertEquals(64_000, context.resolveOutputBudget(),
                "only the last outgoing message's override counts - earlier ones do not stack");
    }

    @Test
    void escalationOnTheSentMessage_isPickedUpWithoutANewConversation() {
        // The truncation escalation: the client bumps the sent message's budget to the model
        // ceiling, and the dispatcher's re-run re-reads resolveOutputBudget on the same conversation.
        ModelSpec small = TestModels.small();
        ConversationContext context = TestModels.conversation(small);
        context.setOutputDeclaration(OutputDeclaration.of(OutputSize.COMPACT));
        OutgoingMessage<String> msg = message("question");
        context.getMessages().add(msg);
        assertEquals(small.getOutputBudget(OutputSize.COMPACT), context.resolveOutputBudget(), "first attempt reserves the declaration");

        msg.setRequestedOutputTokens(small.getMaxOutputTokens());

        assertEquals(small.getMaxOutputTokens(), context.resolveOutputBudget(),
                "second attempt picks up the escalation without constructing a new conversation");
    }

    @Test
    void overrideAboveModelCeiling_capsAtCeiling() {
        ModelSpec small = TestModels.small();
        ConversationContext context = TestModels.conversation(small);
        OutgoingMessage<String> msg = message("hello");
        msg.setRequestedOutputTokens(small.getMaxOutputTokens() + 1);
        context.getMessages().add(msg);

        assertEquals(small.getMaxOutputTokens(), context.resolveOutputBudget(),
                "a caller cannot request more output than the model can emit");
    }

    @Test
    void declaredCountAboveModelCeiling_capsAtCeiling() {
        // The regression this cap exists for: a budget above the model's ceiling put a
        // max_tokens on the wire that the provider rejected outright, making every call fail.
        ModelSpec micro = TestModels.micro();
        ConversationContext context = TestModels.conversation(micro);
        context.setOutputDeclaration(OutputDeclaration.of(micro.getMaxOutputTokens() * 4));

        assertEquals(micro.getMaxOutputTokens(), context.resolveOutputBudget(),
                "a declaration never exceeds what the model can emit");
    }

    @Test
    void maxRung_isTheModelCeiling() {
        ModelSpec small = TestModels.small();
        ConversationContext context = TestModels.conversation(small);
        context.setOutputDeclaration(OutputDeclaration.of(OutputSize.MAX));

        assertEquals(small.getMaxOutputTokens(), context.resolveOutputBudget());
    }

    @Test
    void outputReserve_addsTheDepthsThinkingAndClampsAtTheCeiling() {
        ModelSpec small = TestModels.small();
        ConversationContext context = TestModels.conversation(small);
        context.setOutputDeclaration(OutputDeclaration.of(OutputSize.COMPACT));
        context.setDepth(Depth.IMMEDIATE);
        assertEquals(small.getOutputBudget(OutputSize.COMPACT), context.outputReserve(small),
                "IMMEDIATE on an Anthropic mode books no reasoning");

        context.setDepth(Depth.STANDARD);
        assertEquals(Math.min(small.getOutputBudget(OutputSize.COMPACT) + small.getThinkingBudget(Depth.STANDARD), small.getMaxOutputTokens()),
                context.outputReserve(small), "STANDARD books the entry's thinking budget on top, as the wire will");

        context.setOutputDeclaration(OutputDeclaration.of(OutputSize.MAX));
        assertEquals(small.getMaxOutputTokens(), context.outputReserve(small),
                "MAX plus thinking clamps at the ceiling, the same clamp the wire applies");
    }
}
