/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.server;

import io.modelcontextprotocol.common.*;

/**
 * Names the consumer behind an MCP request. The host supplies it: a Spring host reads
 * the authenticated principal its transport's context extractor recorded, a servlet
 * host reads the session user. The name it returns becomes the principal of every job
 * the served tool runs as ({@code Job.workflow(consumer, "mcp")}), which is what the
 * tool's own auth and admission guardrails judge; the groups it returns are what the
 * host's {@link McpAccessPolicy} may grant by.
 * <p>
 * Null or a throw both mean unauthenticated. The serving layer fails closed on each:
 * nothing is listed and no tool is called.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-02)
 */
public interface McpConsumerResolver {
    McpConsumer resolve(McpTransportContext context);
}
