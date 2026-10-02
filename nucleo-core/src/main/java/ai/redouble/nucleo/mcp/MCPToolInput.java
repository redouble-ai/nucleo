/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp;

import com.fasterxml.jackson.databind.*;

/**
 * Input wrapper for MCP tool calls: the raw {@link JsonNode} arguments, untyped because
 * the remote server is the schema authority.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-01-10)
 */
public class MCPToolInput  {
    private JsonNode arguments;

    public MCPToolInput() {
    }

    public JsonNode getArguments() {
        return arguments;
    }

    public void setArguments(JsonNode arguments) {
        this.arguments = arguments;
    }
}
