/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.sommelier;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.registry.*;
import ai.redouble.nucleo.tools.thinking.*;
import org.slf4j.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * The tool a thinker with a long catalog calls to learn which tools serve a need. It puts
 * the need and the thinker's catalog to the deployment's {@link Sommelier} and returns the
 * tools the sommelier named, each with its description and whether the thinker already
 * holds it.
 *
 * <p>It names tools and admits none: a tool reaches the thinker through
 * {@code request_tools} and the admission rules registered for it.
 *
 * @author Andrey Santrosyan
 * @since 0.1.5 (2026-11-21)
 */
@DisplayName(value = "Find Tools", action = "Finding Tools")
@ToolName(FindToolsTool.NAME)
@LLMDescription("Find the tools that serve a need. More tools are available than are listed to you: say what you have and what you want, and the answer names the tools that fit. Activate the ones you choose with request_tools.")
@ReadOnly
@ToolWeight(type = ToolType.IN_MEMORY, min = 1, max = 1)
public class FindToolsTool extends AbstractDoer<FindToolsInput, FindToolsResult> implements ToolRegistryAware {
    private static final Logger log = LoggerFactory.getLogger(FindToolsTool.class);

    /** Stable name used for registry lookups and unregistration. */
    public static final String NAME = "find_tools";

    private ToolRegistry toolRegistry;
    private Thinker<?, ?> thinker;

    public FindToolsTool(Identifiable parent) {
        super(parent, "find-tools");
    }

    @Override
    public void setToolRegistry(ToolRegistry registry) {
        this.toolRegistry = registry;
    }

    @Override
    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    @Override
    public void setThinker(Thinker<?, ?> thinker) {
        this.thinker = thinker;
    }

    @Override
    public Thinker<?, ?> getThinker() {
        return thinker;
    }

    @Override
    public FindToolsResult execute(JobContext<FindToolsResult> context) throws LLMReadableCheckedException {
        String need = getInput().getNeed();
        if (need == null || need.isBlank()) {
            throw new InvalidInputException("need", null, "Say what you have and what you want");
        }
        ToolHub hub = ToolHub.getInstance();
        Set<ToolProvider> catalog = hub.catalogOf(thinker);
        SommelierAnswer answer;
        try {
            answer = submitInStep(hub.getSommelier().pair(this, need, catalog)).get();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SystemException("sommelier", "interrupted while the tool catalog was being read", e);
        }
        catch (ExecutionException e) {
            throw LLMReadableCheckedException.unwrap(e);
        }
        List<FindToolsResult.FoundTool> found = new ArrayList<>();
        Set<String> offered = new HashSet<>();
        for (ToolProvider provider : catalog) {
            offered.add(provider.name());
            if (answer.getFitting().contains(provider.name())) {
                FindToolsResult.FoundTool tool = new FindToolsResult.FoundTool();
                tool.setName(provider.name());
                tool.setDescription(provider.description());
                tool.setLoaded(toolRegistry.hasTool(provider.name()));
                found.add(tool);
            }
        }
        for (String name : answer.getFitting()) {
            if (!offered.contains(name)) {
                log.warn("ToolHub: the sommelier named a tool outside the catalog of {}, dropped: {}", thinker.getClass().getSimpleName(), name);
            }
        }
        log.info("ToolHub: find_tools for {} - {} of {} tools fit", thinker.getClass().getSimpleName(), found.size(), catalog.size());
        FindToolsResult result = new FindToolsResult();
        result.setTools(found);
        result.setCatalogSize(catalog.size());
        return result;
    }
}
