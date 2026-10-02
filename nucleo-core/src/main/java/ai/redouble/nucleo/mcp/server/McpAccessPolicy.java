/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.server;

import ai.redouble.nucleo.tools.registry.*;

/**
 * Decides whether a consumer may use an exposable tool. One predicate serves both
 * listing and calling, so a consumer sees exactly the tools it may call and an
 * ungranted call is answered exactly like a call to a tool that does not exist. The
 * host supplies it: a host with no identity system answers from configuration keyed by
 * the consumer's name or any of its groups ({@link StaticGrantsAccessPolicy}), a host
 * built on a platform that models roles and actions answers from that registry.
 * <p>
 * A throw is treated as a refusal and logged: policy outages never widen access.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-02)
 */
public interface McpAccessPolicy {
    boolean admits(McpConsumer consumer, ToolProvider provider);
}
