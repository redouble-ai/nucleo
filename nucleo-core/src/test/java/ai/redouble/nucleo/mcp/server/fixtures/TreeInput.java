/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.server.fixtures;

import ai.redouble.nucleo.harness.schema.*;
import java.util.*;

/**
 * A recursive input: a node whose children are nodes. Its published schema describes the
 * type once under {@code $defs} and refers to it from {@code children}, so admitting an
 * instance means following that reference for every level below the root.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-06)
 */
public class TreeInput {
    @LLMRequired
    @LLMDescription("What this node is called")
    private String label;

    @LLMDescription("Nodes below this one")
    private List<TreeInput> children;

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public List<TreeInput> getChildren() {
        return children;
    }

    public void setChildren(List<TreeInput> children) {
        this.children = children;
    }
}
