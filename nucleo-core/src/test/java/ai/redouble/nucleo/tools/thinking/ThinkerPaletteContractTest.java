/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.builtin.*;
import ai.redouble.nucleo.tools.registry.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the per-turn palette contract: the default {@code reconcileToolRegistry} hook
 * offers {@code sub_thinker} exactly when the thinker's own depth reaches STANDARD
 * (QUICK and IMMEDIATE thinkers cannot delegate), the hook is idempotent (state-based -
 * running it twice yields the same registry), and {@link ToolRegistry}'s register and
 * unregister are no-ops when the registry is already in the desired state, which is what
 * makes hooks safe to run every turn.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-31)
 */
public class ThinkerPaletteContractTest {

    private static Identifiable root() {
        return Job.workflow("palette-test", "palette-test");
    }

    static final class ProbeThinker extends AbstractThinker<ThinkerInput, VoidThinkerOutput> {
        ProbeThinker(Identifiable parent) {
            super(parent, new ThinkerDeclaration(Grade.SMALL, OutputSize.COMPACT));
        }

        @Override
        protected List<Class<? extends Tool>> declareDefaultTools() {
            return List.of();
        }

        @Override
        public Depth getDepth() {
            return getInput().getDepth();
        }

        @Override
        protected void runThinkingLoop(ConversationContext conversation, JobContext<VoidThinkerOutput> context) {
        }

        @Override
        protected VoidThinkerOutput getResult() {
            return null;
        }

        Set<String> reconciled() {
            reconcileToolRegistry();
            return toolRegistry.getRegisteredToolNames();
        }
    }

    private static ThinkerInput input(Depth depth) {
        SubThinkerInput input = new SubThinkerInput();
        input.setQuery("probe");
        input.setDepth(depth);
        return input;
    }

    @Test
    void subThinkerIsOfferedAtStandardDepth_andWithheldBelowIt() {
        ProbeThinker quick = new ProbeThinker(root());
        quick.setInput(input(Depth.QUICK));
        assertFalse(quick.reconciled().contains("sub_thinker"),
                "a QUICK thinker cannot delegate - the palette must not tempt it");

        ProbeThinker standard = new ProbeThinker(root());
        standard.setInput(input(Depth.STANDARD));
        assertTrue(standard.reconciled().contains("sub_thinker"),
                "at STANDARD the delegation tool is on the palette");
    }

    @Test
    void reconcileIsIdempotent_andTracksDepthAsState() {
        ProbeThinker thinker = new ProbeThinker(root());
        thinker.setInput(input(Depth.STANDARD));
        Set<String> first = Set.copyOf(thinker.reconciled());
        Set<String> second = Set.copyOf(thinker.reconciled());
        assertEquals(first, second, "the hook is state-based: same state in, same registry out");

        thinker.setInput(input(Depth.QUICK));
        assertFalse(thinker.reconciled().contains("sub_thinker"),
                "a depth change is reflected on the next reconcile - the registry follows state, not history");
    }

    @Test
    void registryRegisterAndUnregisterAreStateIdempotent() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(CurrentTimeTool.class);
        registry.register(CurrentTimeTool.class);
        assertEquals(1, registry.getAllProviders().size(),
                "a second registration of the same class changes nothing");
        registry.unregister("no_such_tool");
        assertTrue(registry.hasToolClass(CurrentTimeTool.class),
                "unregistering an absent name is a no-op, not an error");
        registry.unregister(CurrentTimeTool.class);
        assertFalse(registry.hasToolClass(CurrentTimeTool.class));
        registry.unregister(CurrentTimeTool.class);
        assertEquals(0, registry.getAllProviders().size(), "double unregister stays a no-op");
    }
}
