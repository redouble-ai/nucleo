/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.server;

import java.util.*;

/**
 * The principal behind an MCP request as the host resolved it: the name every served tool
 * runs as ({@code Job.workflow(name, "mcp")}) and the groups the access policy may grant
 * by. Groups are the one attribute every host has - a Spring host reads them from the
 * token's user object, a servlet host from the session user - and the policy is the one
 * place they matter, so they travel with the name rather than being looked up again
 * downstream.
 *
 * @param name   the principal; never null or blank
 * @param groups the group names the principal belongs to, transitively; never null, may be empty
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
public record McpConsumer(String name, Set<String> groups) {
    public McpConsumer {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A consumer needs a name");
        }
        if (groups == null) {
            throw new IllegalArgumentException("A consumer's groups are a set, empty when it has none");
        }
        groups = Set.copyOf(groups);
    }
}
