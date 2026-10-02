/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.prompt.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.registry.*;

import java.util.*;

/**
 * Dynamic sub-agent that any thinker can spawn at runtime.
 *
 * <p>Inherits all tools from the parent thinker automatically. The parent provides
 * a natural language objective (which can reference artifacts), and the sub-agent
 * runs an independent thinking loop with its own conversation context.
 *
 * <p><b>Constraints travel with the capabilities.</b> The submission door seals the
 * spawning orchestrator's {@code ScopeGuard} onto this delegate at dispatch, and that
 * guard carries both the flow's identity axes and its read-only binding. A sub-agent
 * of a ticket-bound flow is bound to the same ticket; a sub-agent of a read-only flow
 * is read-only itself. Nothing here arranges it: inheritance is a property of the one
 * door every submission crosses.
 *
 * <p>Typed artifacts produced by tool calls flow back to the parent through the
 * standard artifact registry pipeline - no data corruption, no parsing from prose.
 *
 * <p><b>Preventing convergence:</b> when the parent spawns multiple sub-agents
 * over the same corpus, it should populate {@link SubThinkerInput#getExclusions()}
 * on each call with topics the other sub-agents are already covering. The
 * exclusions are appended to the objective so each sub-agent steers toward
 * different retrieval queries instead of converging on the same top-ranked results.
 *
 * <p>Recursion control: the delegate's {@link #delegationThreshold()} is THOROUGH, so a
 * sub-agent dispatched at QUICK or STANDARD does its work directly and only a
 * THOROUGH/ULTRA_THOROUGH one may delegate further - a single level of delegation is the
 * norm, never an unbounded chain. The sub-agent's own depth (passed by the parent on the
 * tool call) controls how hard <em>it</em> works, not whether it may run.
 *
 * <p><b>The catalog travels with the tools.</b> At construction the delegate captures the
 * parent's reconciled {@code request_tools} and {@code request_skill} catalogs, and its
 * {@link #reconcileCatalog} / {@link #reconcileSkillCatalog} answer with that capture, so
 * its own doors offer exactly what the parent's offered at the moment of launch. The
 * parent's door instances themselves are not copied - they are per-turn catalog snapshots
 * the delegate's own reconcile rebuilds.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-03-29)
 */
@ToolName("sub_thinker")
@ToolDescription("Spawn a focused sub-agent to investigate a specific aspect of the problem. " +
        "Use for decomposition by aspect (different angles on one subject), never to apply " +
        "the same operation to every iterand of a list - that belongs to the fan-out tools " +
        "when available (see the 'delegation' skill). " +
        "Provide a clear objective in the query field, and set depth to tell the sub-agent " +
        "how hard to work (QUICK for a fast lookup, STANDARD for normal effort, " +
        "THOROUGH if it should itself delegate further). " +
        "The sub-agent has access to all your tools and works independently, " +
        "returning findings with preserved artifacts. " +
        "When spawning multiple sub-agents over the same data, use the exclusions field " +
        "to list topics or angles that other sub-agents are already covering - " +
        "this prevents them from converging on the same findings.")
public class SubThinker extends SingleObjectiveThinker<SubThinkerInput, SubThinkerOutput> {
    private final String parentDisplayName;
    /** The parent's reconciled tool catalog, captured at launch; what this delegate's request_tools offers. */
    private final Set<ToolProvider> parentCatalog;
    /** The parent's reconciled skill catalog, captured at launch; what this delegate's request_skill offers. */
    private final Set<ai.redouble.nucleo.prompt.skill.Skill> parentSkillCatalog;

    /** The spawning seat's grade; a sub-agent has no seat of its own to declare one from. */
    private static Grade inheritedGrade(Identifiable parent) {
        if (parent instanceof ModelDependent parentSeat && parentSeat.getGrade() != null) {
            return parentSeat.getGrade();
        }
        throw new IllegalArgumentException("SubThinker needs a spawning seat with a grade to inherit; parent "
                + (parent != null ? parent.getClass().getSimpleName() : "null") + " declares none");
    }

    public SubThinker(Identifiable parent) {
        // A delegate works at its parent's grade: the aspect it investigates is a slice of
        // the parent's problem, and the parent chose its rung for that problem (the parent
        // may still raise or lower it after construction). Its answer size is its OWN:
        // SubThinkerOutput is a findings POJO with a list of results, the typical structured
        // thinker answer, and the parent's rung was sized to the PARENT's answer - an
        // EXTENDED report writer must not book EXTENDED on every delegate turn.
        super(parent, new ThinkerDeclaration(inheritedGrade(parent), OutputSize.STANDARD));
        this.parentDisplayName = JobSnapshot.extractDisplayName(parent instanceof Job<?> j ? j.getClass() : null);
        this.setAnswerHandler(new PojoResponseHandler<>(SubThinkerOutput.class));
        if (parent instanceof Thinker<?, ?> parentThinker) {
            // Inherit the parent's provider INSTANCES, not their classes. A
            // provider can capture run-time wiring the bare class cannot
            // reconstruct - an McpLoaderProvider's private MCP registry, a
            // TableLoaderProvider's query id, an ActionGatedMcpProvider's gate,
            // or an MCP-backed provider's dynamic tool name. Re-registering by
            // class (ClassToolProvider.of(...)) drops all of it, so the
            // sub-agent inherits a tool that throws the moment it runs. A
            // provider is an immutable descriptor plus a factory whose create()
            // binds each tool instance to ITS caller, so sharing the descriptor
            // shares no live state. Copying the instances makes "the sub-agent
            // has access to all your tools" actually hold.
            for (ToolProvider provider : parentThinker.getProviders()) {
                if (RequestToolsProvider.NAME.equals(provider.name()) || RequestSkillProvider.NAME.equals(provider.name())) {
                    // per-turn catalog snapshots, rebuilt by this delegate's own reconcile
                    continue;
                }
                addTool(provider);
            }
        }
        if (parent instanceof AbstractThinker<?, ?> parentThinker) {
            this.parentCatalog = parentThinker.reconcileCatalog(ToolHub.getInstance().resolveCompatibleTools(parentThinker));
            this.parentSkillCatalog = parentThinker.reconcileSkillCatalog(ToolHub.getInstance().resolveCompatibleSkills(parentThinker));
        }
        else {
            this.parentCatalog = Set.of();
            this.parentSkillCatalog = Set.of();
        }
    }

    /**
     * The parent's tool catalog, captured at this delegate's launch. The hub resolves a
     * catalog per thinker CLASS, which for SubThinker would freeze the first parent's
     * catalog process-wide; answering from the capture keeps each delegate's catalog its
     * own parent's.
     */
    @Override
    protected Set<ToolProvider> reconcileCatalog(Set<ToolProvider> compatible) {
        return parentCatalog;
    }

    /** The parent's skill catalog, captured at launch, for the same reason as {@link #reconcileCatalog}. */
    @Override
    protected Set<ai.redouble.nucleo.prompt.skill.Skill> reconcileSkillCatalog(Set<ai.redouble.nucleo.prompt.skill.Skill> compatible) {
        return parentSkillCatalog;
    }

    @Override
    public String getDisplayName() {
        return parentDisplayName != null ? "Sub-Agent of " + parentDisplayName : "Sub-Agent";
    }

    @Override
    protected List<Class<? extends Tool>> declareDefaultTools() {
        return List.of();
    }

    /**
     * Stricter than the default: a sub-agent may only spawn further sub-agents when
     * its own depth is THOROUGH or above. This bounds recursive delegation - a
     * SubThinker dispatched at QUICK/STANDARD does its work directly.
     */
    @Override
    protected Depth delegationThreshold() {
        return Depth.THOROUGH;
    }

    @StaticPrompt
    @Override
    protected String getSystemPromptText() {
        return """
            You are a sub-agent investigating a specific aspect of a larger research effort.
            Focus on the query provided in the input.

            If the input contains an 'exclusions' list, those topics are being investigated
            by other sub-agents. Do NOT cover them; focus exclusively on aspects not in that
            list, and pick retrieval queries that steer away from their expected results.
            """;
    }
}
