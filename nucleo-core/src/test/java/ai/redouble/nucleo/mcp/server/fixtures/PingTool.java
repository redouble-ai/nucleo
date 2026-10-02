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
 * Served leaf with no input fields: a call with no arguments is legal.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-02)
 */
@MCP
@ToolName("mcp_ping")
@ToolDescription(value = "Answers pong.", readOnly = true)
public class PingTool extends AbstractTool<NoFieldsInput, String> {
    public PingTool(Identifiable parent) {
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
        return "pong";
    }
}
