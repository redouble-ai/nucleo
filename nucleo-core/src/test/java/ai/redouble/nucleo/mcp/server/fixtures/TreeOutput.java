/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.server.fixtures;

import ai.redouble.nucleo.harness.schema.*;
import java.util.*;

/**
 * A self-referential result, so the published output schema contains a {@code $ref} into a
 * {@code $defs} section. Present to prove the SDK's own validator resolves one: a schema it
 * could not resolve would fail every call to the tool, and the failure would only appear
 * over a real transport.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
public class TreeOutput {
    @LLMDescription("What this node is called")
    private String label;

    @LLMDescription("Nodes below this one")
    private List<TreeOutput> children;

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public List<TreeOutput> getChildren() {
        return children;
    }

    public void setChildren(List<TreeOutput> children) {
        this.children = children;
    }
}
