/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.prompt.*;
import ai.redouble.nucleo.prompt.skill.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.builtin.*;
import ai.redouble.nucleo.tools.registry.*;
import ai.redouble.nucleo.tools.thinking.*;
import java.util.*;

/**
 * The demo's one agent: a goal-directed thinker with two tools of its own, a catalog of tools
 * it may ask for through {@code request_tools}, and a catalog of skills it may admit through
 * {@code request_skill}. The skill catalog is everything the registry holds, which at startup
 * is the demo's own bundle under {@code META-INF/skills/} plus every skilljar on the
 * classpath, the project's public one among them. Nothing here names a skill: a jar added to
 * the classpath is on the catalog at the next start.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-11)
 */
@ToolName("demo_agent")
@ToolDescription(value = "Answers a query with the demo's tools and whichever skills it admits", readOnly = true)
@ToolWeight(type = ToolType.THINKER, min = 1, max = 8)
public class DemoAgent extends SingleObjectiveThinker<DemoQuery, DemoAnswer> {

    public DemoAgent(Identifiable parent) {
        super(parent, new ThinkerDeclaration(Grade.MEDIUM, OutputSize.COMPACT));
        setAnswerHandler(new PojoResponseHandler<>(DemoAnswer.class));
        setMaxIterations(8);
        setUpstreamRetries(DemoPolicy.UPSTREAM_RETRIES);
    }

    @StaticPrompt
    @Override
    protected String getSystemPromptText() {
        return """
            You answer one query with the tools and skills you are offered.

            Time and dates come from tools, never from memory: get_current_time for now,
            calculate_dates for any arithmetic on dates.

            When the query matches a skill on the request_skill catalog, admit it first and
            follow its instructions for the rest of the conversation; when none applies,
            answer without one. Report every skill you admitted in skills_used, in the order
            you admitted them, and an empty list when you admitted none.
            """;
    }

    @Override
    protected List<Class<? extends Tool>> declareDefaultTools() {
        return List.of(CurrentTimeTool.class, DateCalculatorTool.class);
    }

    @Override
    protected ToolSelector declareCompatibleTools() {
        return new ToolSelector(WebFetchTool.class);
    }

    @Override
    protected SkillSelector declareCompatibleSkills() {
        SkillSelector everything = new SkillSelector();
        everything.addAll();
        return everything;
    }

    /**
     * The palette as the model would see it this turn: every tool name on the registry. The
     * palette depends on the input's depth, so an agent asked before it has one is given the
     * default query.
     */
    public Set<String> palette() {
        if (getInput() == null) {
            DemoQuery capabilities = new DemoQuery();
            capabilities.setQuery("capabilities");
            setInput(capabilities);
        }
        reconcileToolRegistry();
        return toolRegistry.getRegisteredToolNames();
    }

    /** The provider behind one name on the palette, or null when the palette does not carry it. */
    public ToolProvider offered(String toolName) {
        for (ToolProvider provider : toolRegistry.getAllProviders()) {
            if (provider.name().equals(toolName)) {
                return provider;
            }
        }
        return null;
    }

    /** The skills the model may admit, as the reconciled catalog stands now. */
    public List<String> skillCatalog() {
        List<String> names = new ArrayList<>();
        for (Skill skill : reconcileSkillCatalog(ToolHub.getInstance().resolveCompatibleSkills(this))) {
            names.add(skill.name());
        }
        Collections.sort(names);
        return names;
    }
}
