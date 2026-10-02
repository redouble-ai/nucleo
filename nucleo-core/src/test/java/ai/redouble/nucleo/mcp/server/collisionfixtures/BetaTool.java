/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.server.collisionfixtures;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.mcp.*;
import ai.redouble.nucleo.mcp.server.fixtures.*;
import ai.redouble.nucleo.tools.*;

/**
 * Same name as {@link AlphaTool}, different class: a collision the catalog refuses.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-02)
 */
@MCP
@ToolName("mcp_twin")
public class BetaTool extends AbstractTool<NoFieldsInput, String> {
    public BetaTool(Identifiable parent) {
        super(parent);
    }

    @Override
    public String execute(JobResources resources, JobContext<String> context) {
        return "beta";
    }
}
