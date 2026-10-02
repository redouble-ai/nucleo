/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp;

import java.util.*;
import java.util.regex.*;

/**
 * Stable identifier for an MCP connector. Equality and hashing are by string value, so
 * {@code new MCPHandle("github").equals(new MCPHandle("github"))} is {@code true} - handles
 * are identifiers, not object references.
 *
 * <p>The constructor validates eagerly so a typo (an empty string, an upper-case letter,
 * a separator like {@code .} that conflicts with namespacing) is caught at attach time
 * rather than first tool call. The format intentionally constrains the value to keep
 * namespaced tool names (see {@link #namespacedToolName(String)}) within the SDK's
 * tool name length budget after concatenation.
 *
 * <p>Used by {@code MCPConnector}, {@code MCPConnectorRegistry}, and {@code MCPToolProvider}
 * to keep the connector identity, the LLM-facing namespace prefix, and the URL/header-derived
 * client cache key distinct from each other in API surfaces.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-05-08)
 */
public final class MCPHandle {
    private static final Pattern VALID = Pattern.compile("^[a-z][a-z0-9_-]{0,15}$");
    private final String value;

    public MCPHandle(String value) {
        if (value == null || !VALID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "MCP handle must match " + VALID.pattern() + " (got: '" + value + "')");
        }
        this.value = value;
    }

    public String value() {
        return value;
    }

    /**
     * Returns the LLM-facing namespaced tool name. Concatenates the handle with the raw
     * tool name using an underscore separator; e.g.
     * {@code MCPHandle("github").namespacedToolName("create_issue")} yields
     * {@code "github_create_issue"}.
     *
     * <p>The separator is an underscore rather than a dot to satisfy provider-side
     * tool-name validation: Anthropic enforces {@code ^[a-zA-Z0-9_-]{1,128}$} on
     * tool names and rejects requests containing dots, and OpenAI imposes a similar
     * constraint. The handle's own regex already restricts it to that character
     * class, so as long as the raw tool name does too, the result is provider-safe.
     */
    public String namespacedToolName(String rawToolName) {
        if (rawToolName == null || rawToolName.isBlank()) {
            throw new IllegalArgumentException("rawToolName must be non-blank");
        }
        return value + "_" + rawToolName;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MCPHandle other && Objects.equals(other.value, this.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "MCPHandle[" + value + "]";
    }
}
