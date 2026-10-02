/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.registry;

import ai.redouble.nucleo.tools.thinking.*;
/**
 * Interface for tools that need access to the thinker's tool registry and thinker reference.
 * The thinker injects both before tool execution, same pattern
 * as {@link ai.redouble.nucleo.harness.artifacts.tools.ArtifactRegistryAware}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-03-14)
 */
public interface ToolRegistryAware {
    void setToolRegistry(ToolRegistry registry);
    ToolRegistry getToolRegistry();
    void setThinker(Thinker<?, ?> thinker);
    Thinker<?, ?> getThinker();
}
