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
 * Takes a {@link TreeInput}, so its published input schema carries a {@code $ref} and the
 * gate has to resolve one to judge anything below the root. Served, because a recursive
 * INPUT is the one shape the dialects disagree most about: four of them cannot spell
 * recursion at all and publish a shapeless object where the tree continues, so only a tool
 * like this one exercises what a caller of those paths may send and what is enforced anyway.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-06)
 */
@MCP
@ToolName("mcp_plant")
@DisplayName(value = "Plant", action = "Planting")
@ToolDescription(value = "Counts the nodes of a tree.", readOnly = true)
public class PlantTool extends AbstractTool<TreeInput, String> {
    public PlantTool(Identifiable parent) {
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
        return Integer.toString(count(getInput()));
    }

    private static int count(TreeInput node) {
        int total = 1;
        if (node.getChildren() != null) {
            for (TreeInput child : node.getChildren()) {
                total += count(child);
            }
        }
        return total;
    }
}
