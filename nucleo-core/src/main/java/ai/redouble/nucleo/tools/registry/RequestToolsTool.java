/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.registry;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.thinking.*;
import org.slf4j.*;

/**
 * Meta-tool that activates tools from the catalog.
 *
 * <p>The LLM calls this to request specific tools from the compatible tools catalog.
 * For each requested tool, ToolHub runs admission guardrails lazily. Admitted tools
 * are registered in the thinker's tool registry and their schemas appear on the next
 * LLM call. Rejected tools are returned with reasons.
 *
 * <p>Extends AbstractDoer (resource-free) because it coordinates admission guardrail
 * jobs without needing its own resources. Implements {@link ToolRegistryAware} so the
 * thinker injects its tool registry and thinker reference before execution.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-03-14)
 */
@ToolName("request_tools")
@ToolDescription("Activate tools from the catalog. Pass tool names to load their full schemas so you can call them.")
@ToolWeight(type = ToolType.IN_MEMORY, min = 1, max = 1)
public class RequestToolsTool extends AbstractDoer<RequestToolsInput, RequestToolsResult> implements ToolRegistryAware {
    private static final Logger log = LoggerFactory.getLogger(RequestToolsTool.class);
    private ToolRegistry toolRegistry;
    private Thinker<?, ?> thinker;
    public RequestToolsTool(Identifiable parent) {
        super(parent, "request-tools");
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
    public RequestToolsResult execute(JobContext<RequestToolsResult> context) throws LLMReadableCheckedException {
        RequestToolsInput input = getInput();
        if (input.getToolNames() == null || input.getToolNames().isEmpty()) {
            throw new InvalidInputException("toolNames", null, "At least one tool name is required");
        }
        log.info("ToolHub: {} requesting tools: {}", thinker.getClass().getSimpleName(), input.getToolNames());
        RequestToolsResult result = ToolHub.getInstance().requestTools(thinker, input.getToolNames(), toolRegistry);
        log.info("ToolHub: request_tools result for {} - admitted: {}{}", thinker.getClass().getSimpleName(), result.getAdmitted(),
                (result.getRejected() != null && !result.getRejected().isEmpty()
                    ? ", rejected: " + result.getRejected().stream().map(RequestToolsResult.RejectedTool::getName).toList()
                    : ""));
        return result;
    }
}
