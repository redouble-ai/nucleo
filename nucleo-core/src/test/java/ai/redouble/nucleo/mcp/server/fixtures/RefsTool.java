/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.server.fixtures;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.mcp.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.thinking.*;

/**
 * A served tool whose input is a {@link ThinkerInput}, the one shape that carries
 * {@code artifact_refs}. The boundary removes that field from what it publishes, so this
 * fixture is what proves a caller sending it is refused: against a tool whose input never
 * declared the field, the refusal would prove only that the name is unknown to that tool.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-06)
 */
@MCP
@ToolName("mcp_refs")
@DisplayName(value = "Refs", action = "Asking")
@ToolDescription(value = "Answers with the query it was given.", readOnly = true)
public class RefsTool extends AbstractTool<ThinkerInput, String> {
    public RefsTool(Identifiable parent) {
        super(parent);
    }

    @Override
    public JobRequirements getRequirements() {
        JobRequirements req = new JobRequirements();
        req.setRequiresTransaction(false);
        req.setReadOnly(true);
        return req;
    }

    @Override
    public String execute(JobResources resources, JobContext<String> context) {
        return "asked";
    }
}
