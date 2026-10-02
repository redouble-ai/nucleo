/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp;

import java.lang.annotation.*;

/**
 * Marks a tool as exposable over MCP. Exposable is not exposed: the marker only admits
 * the class into a serving catalog ({@code ai.redouble.nucleo.mcp.server.McpToolCatalog});
 * whether a given consumer may list or call it is the host's access policy, decided per
 * request. A marked class must also carry {@link ai.redouble.nucleo.tools.ToolName}: an
 * exposable tool without a name cannot be published, and the catalog refuses it.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-02)
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface MCP {
}
