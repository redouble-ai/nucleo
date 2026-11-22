/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.sommelier;

import ai.redouble.nucleo.harness.schema.*;

import java.util.*;

/**
 * Output of {@link FindToolsTool}: the tools that fit, and how many tools were read to
 * find them. An empty list says every tool was read and none fits.
 *
 * @author Andrey Santrosyan
 * @since 0.1.5 (2026-11-21)
 */
public class FindToolsResult {
    @LLMDescription("The tools that fit the need. Several that look alike are all listed: choose among them by what you know of the task. Empty when no tool of the catalog fits.")
    private List<FoundTool> tools;
    @LLMDescription("How many tools the catalog holds; every one of them was read")
    private int catalogSize;

    public List<FoundTool> getTools() {
        return tools;
    }

    public void setTools(List<FoundTool> tools) {
        this.tools = tools;
    }

    public int getCatalogSize() {
        return catalogSize;
    }

    public void setCatalogSize(int catalogSize) {
        this.catalogSize = catalogSize;
    }

    /** One tool that fits. */
    public static class FoundTool {
        @LLMDescription("Tool name, to pass to request_tools")
        private String name;
        @LLMDescription("What the tool does")
        private String description;
        @LLMDescription("True when the tool is already available to call, false when it must first be activated with request_tools")
        private boolean loaded;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public boolean isLoaded() {
            return loaded;
        }

        public void setLoaded(boolean loaded) {
            this.loaded = loaded;
        }
    }
}
