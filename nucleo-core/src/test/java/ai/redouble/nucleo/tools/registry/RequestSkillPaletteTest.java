/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.registry;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.prompt.skill.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.thinking.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * How {@code request_skill} reaches a model and how a call to it reaches the conversation,
 * without any model in the loop: the per-turn palette hook offers the tool exactly when the
 * thinker has a skill catalog, the tool's schema is that catalog, a read-only thinker keeps it
 * where it loses {@code request_tools}, and a call submitted through the thinker's own tool
 * path lands the skill in the conversation the thinker handed over.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-11)
 */
public class RequestSkillPaletteTest {
    private static final Skill CITE = SkillFixtures.skill("pal.cite", "How to cite.", "ai.example.pal");
    private static final Skill BRIEF = SkillFixtures.skill("pal.brief", "Be brief.", "ai.example.pal");

    @BeforeAll
    static void startDispatcher() {
        JobDispatcher.getInstance().start();
    }

    @BeforeEach
    void freshHub() {
        ToolHub.getInstance().resetAll();
        SkillRegistry.register(CITE);
        SkillRegistry.register(BRIEF);
    }

    private static Identifiable root() {
        return Job.workflow("palette-user", "request-skill-palette-test");
    }

    static class ProbeThinker extends AbstractThinker<ThinkerInput, VoidThinkerOutput> {
        private final SkillSelector skills;

        ProbeThinker(Identifiable parent, SkillSelector skills) {
            this(parent, skills, false);
        }

        ProbeThinker(Identifiable parent, SkillSelector skills, boolean readOnly) {
            super(parent, new ThinkerDeclaration(Grade.SMALL, OutputSize.COMPACT), readOnly);
            this.skills = skills;
        }

        @Override
        protected List<Class<? extends Tool>> declareDefaultTools() {
            return List.of();
        }

        @Override
        protected SkillSelector declareCompatibleSkills() {
            return skills;
        }

        @Override
        public Depth getDepth() {
            return Depth.STANDARD;
        }

        @Override
        protected void runThinkingLoop(ConversationContext conversation, JobContext<VoidThinkerOutput> context) {
        }

        @Override
        protected VoidThinkerOutput getResult() {
            return null;
        }

        Set<String> palette() {
            reconcileToolRegistry();
            return toolRegistry.getRegisteredToolNames();
        }

        ToolProvider provider(String name) {
            for (ToolProvider provider : toolRegistry.getAllProviders()) {
                if (provider.name().equals(name)) {
                    return provider;
                }
            }
            return null;
        }

        long providersNamed(String name) {
            return toolRegistry.getAllProviders().stream().filter(p -> p.name().equals(name)).count();
        }

        /** The thinker's own tool path, as a model's call would take it, against a conversation it holds. */
        Object callThroughTheThinker(ToolCall call, ConversationContext conversation) throws Exception {
            reconcileToolRegistry();
            JobHandle<?> handle = submitToolCall(call, JobDispatcher.getInstance(), getUserId(), null, conversation, 1);
            return handle.get();
        }
    }

    /** A distinct class, because the hub resolves and caches the catalog per thinker class. */
    static class SkilllessProbe extends ProbeThinker {
        SkilllessProbe(Identifiable parent) {
            super(parent, null);
        }
    }

    @Test
    void requestSkillIsOfferedExactlyWhenThereIsACatalog() {
        ProbeThinker with = new ProbeThinker(root(), new SkillSelector("pal.cite", "pal.brief"));
        assertTrue(with.palette().contains(RequestSkillProvider.NAME), "a catalog puts request_skill on the palette");

        ProbeThinker without = new SkilllessProbe(root());
        assertFalse(without.palette().contains(RequestSkillProvider.NAME), "no catalog, no tool to offer");
    }

    @Test
    void theOfferedSchemaIsTheReconciledCatalog() throws Exception {
        ProbeThinker thinker = new ProbeThinker(root(), new SkillSelector("pal.cite", "pal.brief"));
        thinker.palette();
        ToolProvider provider = thinker.provider(RequestSkillProvider.NAME);
        assertNotNull(provider);
        JsonNode oneOf = NucleoJsonSerializer.readTree(provider.schemaJson())
                .path("properties").path(RequestSkillProvider.SKILL_NAMES).path("items").path("oneOf");
        Set<String> offered = new HashSet<>();
        for (JsonNode entry : oneOf) {
            offered.add(entry.path("const").asText());
        }
        assertEquals(Set.of("pal.cite", "pal.brief"), offered);
    }

    @Test
    void theHookIsIdempotent() {
        ProbeThinker thinker = new ProbeThinker(root(), new SkillSelector("pal.cite"));
        Set<String> first = Set.copyOf(thinker.palette());
        Set<String> second = Set.copyOf(thinker.palette());
        assertEquals(first, second, "same state in, same registry out");
        assertEquals(1, thinker.providersNamed(RequestSkillProvider.NAME));
    }

    @Test
    void aReadOnlyThinkerKeepsRequestSkill_whereItLosesRequestTools() {
        ProbeThinker readOnly = new ProbeThinker(root(), new SkillSelector("pal.cite"), true);
        Set<String> palette = readOnly.palette();
        assertTrue(palette.contains(RequestSkillProvider.NAME),
                "admitting a skill changes the conversation's preamble and nothing outside it");
        ToolRegistry registry = new ToolRegistry();
        registry.register(new RequestSkillProvider(Set.of(CITE)));
        registry.register(new RequestToolsProvider(Set.of(ClassToolProvider.of(ai.redouble.nucleo.tools.builtin.CurrentTimeTool.class))));
        ReadOnlyPalette.sweep(registry, "probe");
        assertTrue(registry.hasTool(RequestSkillProvider.NAME));
        assertFalse(registry.hasTool(RequestToolsProvider.NAME), "request_tools admits arbitrary tools and is swept");
    }

    @Test
    void aCallThroughTheThinkerLandsTheSkillInTheConversationItHolds() throws Exception {
        ProbeThinker thinker = new ProbeThinker(root(), new SkillSelector("pal.cite", "pal.brief"));
        ConversationContext conversation = new ConversationContext();
        RequestSkillInput input = new RequestSkillInput();
        input.setSkillNames(List.of("pal.brief", "pal.nowhere"));
        ToolCall call = new ToolCall(RequestSkillProvider.NAME, input);
        call.setToolUseId("call-1");

        Object result = thinker.callThroughTheThinker(call, conversation);

        assertInstanceOf(RequestSkillResult.class, result);
        RequestSkillResult typed = (RequestSkillResult) result;
        assertEquals(List.of("pal.brief"), typed.getAdmitted());
        assertEquals("pal.nowhere", typed.getRejected().get(0).getName());
        assertEquals(List.of("pal.brief"), conversation.getLoadedSkills().stream().map(Skill::name).toList(),
                "the tool acted on the conversation the thinker handed it, not one it obtained itself");
    }

    @Test
    void theToolRefusesToRunOutsideAThinker() {
        RequestSkillTool tool = new RequestSkillTool(root());
        RequestSkillInput input = new RequestSkillInput();
        input.setSkillNames(List.of("pal.cite"));
        tool.setInput(input);
        java.util.concurrent.ExecutionException failure = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> JobDispatcher.getInstance().submit(tool).get());
        assertInstanceOf(ai.redouble.nucleo.harness.errors.SystemException.class, failure.getCause(),
                "no thinker and no conversation were handed over: a fault of the caller, not of the model");
    }

    @Test
    void anEmptyRequestIsACorrectableInputError() {
        RequestSkillTool tool = new RequestSkillTool(root());
        tool.setInput(new RequestSkillInput());
        java.util.concurrent.ExecutionException failure = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> JobDispatcher.getInstance().submit(tool).get());
        assertInstanceOf(ai.redouble.nucleo.harness.errors.InvalidInputException.class, failure.getCause());
    }
}
