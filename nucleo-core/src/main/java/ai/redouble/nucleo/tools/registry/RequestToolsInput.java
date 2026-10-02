/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.registry;

import ai.redouble.nucleo.harness.schema.*;

import java.util.*;

/**
 * Input for the request_tools meta-tool.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-03-14)
 */
public class RequestToolsInput  {
    @LLMRequired
    @LLMDescription("Names of tools to activate from the catalog")
    private List<String> toolNames;
    public List<String> getToolNames() {
        return toolNames;
    }
    public void setToolNames(List<String> toolNames) {
        this.toolNames = toolNames;
    }
}
