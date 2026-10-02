/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation.compaction;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.models.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the three-level resolution of the context window's two numbers: the comfort window
 * (thinker override, else the catalog entry's declaration, else the framework default,
 * always capped by the hard context) and the compaction trigger (thinker override, else
 * the framework default), plus the absolute fit limit that subtracts the seat's declared
 * output reserve from the hard context.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-06)
 */
class ContextWindowManagerResolutionTest {

    private static final Identifiable ROOT = Job.workflow("window-test", "window-test");

    private static StandardModelSpec spec(int maxContext, Integer comfort) {
        StandardModelSpec spec = new StandardModelSpec();
        spec.setId("window-fake");
        spec.setIdentity("window-fake");
        spec.setProviderKey("openai");
        spec.setWireModelId("window-fake");
        spec.setMaxContextTokens(maxContext);
        spec.setMaxOutputTokens(8_000);
        spec.setTpm(1000);
        spec.setComfortContextTokens(comfort);
        return spec;
    }

    @Test
    void frameworkDefault_whenNothingDeclares() {
        ContextWindowManager manager = new ContextWindowManager(spec(1_000_000, null), ROOT, null, null);
        assertEquals(ContextWindowManager.DEFAULT_COMFORT_CONTEXT_TOKENS, manager.effectiveLimit());
    }

    @Test
    void entryDeclaration_beatsTheDefault() {
        ContextWindowManager manager = new ContextWindowManager(spec(1_000_000, 300_000), ROOT, null, null);
        assertEquals(300_000, manager.effectiveLimit());
    }

    @Test
    void thinkerOverride_beatsTheEntry() {
        ContextWindowManager manager = new ContextWindowManager(spec(1_000_000, 300_000), ROOT, 500_000, null);
        assertEquals(500_000, manager.effectiveLimit());
    }

    @Test
    void hardContext_capsEveryLevel() {
        ContextWindowManager manager = new ContextWindowManager(spec(64_000, null), ROOT, 500_000, null);
        assertEquals(64_000, manager.effectiveLimit(), "a comfort window above the hard context is the hard context");
    }

    @Test
    void overridesAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> new ContextWindowManager(spec(64_000, null), ROOT, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new ContextWindowManager(spec(64_000, null), ROOT, null, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new ContextWindowManager(spec(64_000, null), ROOT, null, 1.5));
    }

    @Test
    void absoluteLimit_leavesRoomForTheDeclaredReserve() {
        StandardModelSpec model = spec(64_000, null);
        ContextWindowManager manager = new ContextWindowManager(model, ROOT, null, null);
        ConversationContext context = new ConversationContext();
        context.setModelBinding(ModelBinding.preResolved(model));
        context.setDepth(Depth.IMMEDIATE);
        context.setOutputDeclaration(OutputDeclaration.of(OutputSize.COMPACT));
        assertEquals(64_000 - model.getOutputBudget(OutputSize.COMPACT), manager.absoluteLimit(context),
                "the hard context minus the seat's own reserve is what is left for input");
    }

    @Test
    void compactionTrigger_isTheOverrideOrTheDefault() {
        StandardModelSpec model = spec(64_000, null);
        ConversationContext context = new ConversationContext();
        context.setModelBinding(ModelBinding.preResolved(model));
        // a conversation between the default trigger and the full window: the tokenizer decides
        // the exact count, so the assertions read the measured total rather than assuming one
        OutgoingMessage<String> big = new OutgoingMessage<>(StringResponseHandler.instance);
        big.addText("lorem ipsum dolor sit amet, consectetur ".repeat(8_700));
        context.getMessages().add(big);
        int total = context.getTotalTokens(model);
        int limit = new ContextWindowManager(model, ROOT, null, null).effectiveLimit();
        assertTrue(total > limit * ContextWindowManager.DEFAULT_COMPACTION_TRIGGER && total <= limit,
                "fixture assumption: the payload sits between the default trigger and the full window, measured " + total);
        assertTrue(new ContextWindowManager(model, ROOT, null, null).needsCompaction(context),
                "above the default 0.92 trigger");
        assertFalse(new ContextWindowManager(model, ROOT, null, 1.0).needsCompaction(context),
                "a thinker that tolerates the full window compacts nothing under it");
    }
}
