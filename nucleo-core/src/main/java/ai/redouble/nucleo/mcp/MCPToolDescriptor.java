/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp;

import com.fasterxml.jackson.databind.*;

/**
 * Describes an MCP tool's schema and metadata.
 * Used for tool discovery and documentation.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-01-10)
 */
public class MCPToolDescriptor {
    private String name;
    private String description;
    private JsonNode inputSchema;

    public MCPToolDescriptor() {
    }

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

    public JsonNode getInputSchema() {
        return inputSchema;
    }

    public void setInputSchema(JsonNode inputSchema) {
        this.inputSchema = inputSchema;
    }
}
