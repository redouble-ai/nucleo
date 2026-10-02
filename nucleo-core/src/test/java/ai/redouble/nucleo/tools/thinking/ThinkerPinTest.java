/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A thinker's pin reaches every call it makes, in every family: the single-objective loop,
 * the reactive loop and the toolless answer all build their calls through the one factory
 * that carries the pin, and a call built without a pin asks for the grade. A benchmark
 * racing a thinker on every model of its grade depends on this; a family that built its
 * own call would race the picker's choice under every model's name.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-17)
 */
class ThinkerPinTest {
    private static final ThinkerDeclaration DECLARATION = new ThinkerDeclaration(Grade.SMALL, OutputSize.COMPACT);

    static final class Single extends SingleObjectiveThinker<ThinkerInput, ThinkerOutput<SimpleReasoning>> {
        Single() {
            super(Job.workflow("test-user", "pin-single"), DECLARATION);
        }

        @Override
        protected List<Class<? extends Tool>> declareDefaultTools() {return List.of();}

        LLMCall<ThinkingResponse<ThinkerOutput<SimpleReasoning>>> call() {
            return newLLMCall(this, new ConversationContext());
        }
    }

    static final class Reactive extends ReactiveThinker {
        Reactive() {
            super(Job.workflow("test-user", "pin-reactive"), DECLARATION);
        }

        @Override
        protected List<Class<? extends Tool>> declareDefaultTools() {return List.of();}

        @Override
        protected void initializeConversation(ConversationContext conversation, JobContext<VoidThinkerOutput> context) {}

        LLMCall<ThinkingResponse<String>> call() {
            return newLLMCall(this, new ConversationContext());
        }
    }

    static final class Toolless extends AbstractToollessThinker<ThinkerInput, VoidThinkerOutput> {
        Toolless() {
            super(Job.workflow("test-user", "pin-toolless"), DECLARATION, VoidThinkerOutput.class);
        }

        @Override
        protected String getSystemPromptText() {return "answer";}

        LLMCall<VoidThinkerOutput> call() {
            return newLLMCall(this, new ConversationContext());
        }
    }

    @Test
    void everyFamilyBuildsItsCallsOnThePin() {
        ModelSpec pin = TestModels.small();
        Single single = new Single();
        Reactive reactive = new Reactive();
        Toolless toolless = new Toolless();
        assertNull(single.call().pinnedModel(), "unpinned, the call asks for the grade");
        single.pinModel(pin);
        reactive.pinModel(pin);
        toolless.pinModel(pin);
        assertSame(pin, single.call().pinnedModel());
        assertSame(pin, reactive.call().pinnedModel());
        assertSame(pin, toolless.call().pinnedModel());
    }

    @Test
    void everyFamilyHandsItsUpstreamRetryBudgetToItsCalls() {
        Single single = new Single();
        Reactive reactive = new Reactive();
        Toolless toolless = new Toolless();
        assertEquals(Job.DEFAULT_UPSTREAM_RETRIES, single.call().getUpstreamRetries(), "a thinker that set nothing hands the default down");
        single.setUpstreamRetries(1);
        reactive.setUpstreamRetries(1);
        toolless.setUpstreamRetries(0);
        assertEquals(1, single.call().getUpstreamRetries(), "the calls wait on a failing endpoint no longer than the thinker would");
        assertEquals(1, reactive.call().getUpstreamRetries());
        assertEquals(0, toolless.call().getUpstreamRetries());
    }

    @Test
    void aPinnedCallRequiresItsEntryAndAnUnpinnedOneItsGrade() {
        ConversationContext conversation = new ConversationContext();
        conversation.setGrade(Grade.SMALL);
        conversation.setDepth(Depth.IMMEDIATE);
        conversation.setOutputDeclaration(OutputDeclaration.of(OutputSize.COMPACT));
        Single single = new Single();
        LLMCall<ThinkingResponse<ThinkerOutput<SimpleReasoning>>> unpinned = single.newLLMCall(single, conversation);
        JobRequirements plain = unpinned.getRequirements();
        assertEquals(1, plain.getModelBindings().size());
        assertNull(plain.getModelBindings().get(0).getPinnedSpec());
        assertEquals(Grade.SMALL, plain.getModelBindings().get(0).getGrade());
        single.pinModel(TestModels.grade(Grade.MEDIUM));
        LLMCall<ThinkingResponse<ThinkerOutput<SimpleReasoning>>> pinned = single.newLLMCall(single, conversation);
        JobRequirements exact = pinned.getRequirements();
        assertSame(TestModels.grade(Grade.MEDIUM), exact.getModelBindings().get(0).getPinnedSpec(), "the binding names the entry, so the dispatcher never consults the picker");
    }
}
