/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.prompt.*;
import ai.redouble.nucleo.prompt.skill.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.builtin.*;
import ai.redouble.nucleo.tools.registry.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A delegate's reach is its parent's, decided at launch: the {@code SubThinker}
 * constructor captures the parent's reconciled tool and skill catalogs, its own
 * {@code request_tools}/{@code request_skill} doors are rebuilt from that capture (the
 * parent's door instances, being per-turn snapshots, are never copied), the hub's
 * admission lookup honours the same capture, a delegate of a delegate still sees the
 * original catalog, and {@code sub_thinker} joins the delegate's own palette only at
 * THOROUGH.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-18)
 */
class SubThinkerCatalogTest {
    private static final Skill BRIEF = skill("subcat.brief", "Be brief.");

    @BeforeAll
    static void startDispatcher() {
        JobDispatcher.getInstance().start();
    }

    @BeforeEach
    void freshHub() {
        ToolHub.getInstance().resetAll();
        SkillRegistry.register(BRIEF);
    }

    private static Skill skill(String name, String description) {
        SkillMetadata metadata = new SkillMetadata();
        metadata.setBundleId("subcat.bundle");
        metadata.setOrigin("test");
        return new TextSkill(name, new TextPrompt(name + ":description", TextNode.valueOf(description)),
                new TextPrompt(name + ":body", TextNode.valueOf("Body of " + name)), Map.of(), List.of(), metadata);
    }

    private static Identifiable root() {
        return Job.workflow("subthinker-catalog-test", "subthinker-catalog-test");
    }

    /** A parent with a palette (the clock), a tool catalog (the two calculators) and a skill catalog. */
    static class ParentThinker extends AbstractThinker<ThinkerInput, VoidThinkerOutput> {
        ParentThinker(Identifiable parent) {
            super(parent, new ThinkerDeclaration(Grade.SMALL, OutputSize.COMPACT));
        }

        @Override
        protected List<Class<? extends Tool>> declareDefaultTools() {
            return List.of(CurrentTimeTool.class);
        }

        @Override
        protected ToolSelector declareCompatibleTools() {
            return new ToolSelector(DateCalculatorTool.class, DurationCalculatorTool.class);
        }

        @Override
        protected SkillSelector declareCompatibleSkills() {
            return new SkillSelector("subcat.brief");
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
    }

    private static SubThinker delegateOf(AbstractThinker<?, ?> parent, Depth depth) {
        SubThinker sub = new SubThinker(parent);
        SubThinkerInput input = new SubThinkerInput();
        input.setQuery("investigate");
        input.setDepth(depth);
        sub.setInput(input);
        return sub;
    }

    private static Set<String> catalogEnum(ToolRegistry registry, String doorName, String field) throws Exception {
        ToolProvider door = registry.getProviderByName(doorName);
        assertNotNull(door, doorName + " must be on the palette");
        JsonNode oneOf = NucleoJsonSerializer.readTree(door.schemaJson())
                .path("properties").path(field).path("items").path("oneOf");
        Set<String> offered = new LinkedHashSet<>();
        for (JsonNode entry : oneOf) {
            offered.add(entry.path("const").asText());
        }
        return offered;
    }

    @Test
    void theDelegatesDoorsOfferExactlyTheParentsCatalogs() throws Exception {
        ParentThinker parent = new ParentThinker(root());
        parent.reconcileToolRegistry();
        SubThinker sub = delegateOf(parent, Depth.STANDARD);
        assertFalse(sub.toolRegistry.hasTool(RequestToolsProvider.NAME),
                "the parent's door instance is a per-turn snapshot and must not arrive by copy");
        assertFalse(sub.toolRegistry.hasTool(RequestSkillProvider.NAME));
        assertSame(parent.toolRegistry.getProviderByName("get_current_time"),
                sub.toolRegistry.getProviderByName("get_current_time"),
                "the parent's palette arrives as the SAME provider instances, never a class re-wrap"
                        + " - a dynamically wired provider re-wrapped by class would lose its runtime state");

        sub.reconcileToolRegistry();
        assertEquals(Set.of("calculate_dates", "calculate_duration"),
                catalogEnum(sub.toolRegistry, RequestToolsProvider.NAME, RequestToolsProvider.TOOL_NAMES),
                "the delegate's request_tools offers the parent's catalog, captured at launch");
        assertEquals(Set.of("subcat.brief"),
                catalogEnum(sub.toolRegistry, RequestSkillProvider.NAME, RequestSkillProvider.SKILL_NAMES),
                "the delegate's request_skill offers the parent's skill catalog");
    }

    @Test
    void theHubAdmitsAParentCatalogToolIntoTheDelegatesRegistry() throws LLMReadableCheckedException {
        ParentThinker parent = new ParentThinker(root());
        SubThinker sub = delegateOf(parent, Depth.STANDARD);
        RequestToolsResult result = ToolHub.getInstance().requestTools(
                sub, List.of("calculate_dates", "web_fetch"), sub.toolRegistry);
        assertEquals(List.of("calculate_dates"), result.getAdmitted(),
                "admission judges the delegate against the CAPTURED parent catalog, not the empty class-keyed one");
        assertEquals("web_fetch", result.getRejected().get(0).getName());
        assertTrue(result.getRejected().get(0).getReason().contains("not found in compatible tools catalog"),
                "a name the parent could not reach, the delegate cannot reach either");
        assertTrue(sub.toolRegistry.hasTool("calculate_dates"));
    }

    @Test
    void aDelegateOfADelegateStillSeesTheOriginalCatalog() throws Exception {
        ParentThinker parent = new ParentThinker(root());
        SubThinker child = delegateOf(parent, Depth.THOROUGH);
        SubThinker grandchild = delegateOf(child, Depth.STANDARD);
        grandchild.reconcileToolRegistry();
        assertEquals(Set.of("calculate_dates", "calculate_duration"),
                catalogEnum(grandchild.toolRegistry, RequestToolsProvider.NAME, RequestToolsProvider.TOOL_NAMES),
                "the capture chains: a delegate's delegate inherits the same reach, undiminished and unwidened");
    }

    @Test
    void aDelegateMayDelegateOnlyAtThorough() {
        ParentThinker parent = new ParentThinker(root());
        SubThinker working = delegateOf(parent, Depth.STANDARD);
        working.reconcileToolRegistry();
        assertFalse(working.toolRegistry.hasTool("sub_thinker"),
                "a STANDARD delegate does its work directly - one level of delegation, never a chain");
        SubThinker delegating = delegateOf(parent, Depth.THOROUGH);
        delegating.reconcileToolRegistry();
        assertTrue(delegating.toolRegistry.hasTool("sub_thinker"),
                "at THOROUGH the delegate may itself delegate");
    }
}
