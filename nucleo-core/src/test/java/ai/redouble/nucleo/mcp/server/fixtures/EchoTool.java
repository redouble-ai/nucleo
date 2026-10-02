/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.server.fixtures;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.mcp.*;
import ai.redouble.nucleo.tools.*;

/**
 * Served leaf: echoes its input together with the principal it ran under.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-02)
 */
@MCP
@ToolName("mcp_echo")
@DisplayName(value = "Echo", action = "Echoing")
@ToolDescription(value = "Echoes text back with the calling principal.", readOnly = true)
public class EchoTool extends AbstractTool<EchoInput, EchoOutput> {
    public EchoTool(Identifiable parent) {
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
    public EchoOutput execute(JobResources resources, JobContext<EchoOutput> context) {
        return new EchoOutput(getInput().getText(), context.getUserId());
    }
}
