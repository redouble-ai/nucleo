/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.server.fixtures;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.mcp.*;
import ai.redouble.nucleo.tools.*;

import java.util.*;

/**
 * Answers with a nested tree, so a call exercises a published output schema that carries a
 * {@code $ref} and the structured content validated against it.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
@MCP
@ToolName("mcp_tree")
@DisplayName(value = "Tree", action = "Growing")
@ToolDescription(value = "Answers with a small tree of nodes.", readOnly = true)
public class TreeTool extends AbstractTool<NoFieldsInput, TreeOutput> {
    public TreeTool(Identifiable parent) {
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
    public TreeOutput execute(JobResources resources, JobContext<TreeOutput> context) {
        TreeOutput leaf = new TreeOutput();
        leaf.setLabel("leaf");
        TreeOutput root = new TreeOutput();
        root.setLabel("root");
        root.setChildren(List.of(leaf));
        return root;
    }
}
