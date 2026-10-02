/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts.tools;

import ai.redouble.nucleo.harness.artifacts.*;


/**
 * Interface for tools that need access to the conversation's artifact registry.
 *
 * <p>Tools implementing this interface will have the artifact registry
 * injected by the thinker before execution. This enables tools like
 * get_artifact_field and search_artifact_content to access artifact data.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-01-10)
 */
public interface ArtifactRegistryAware {
    /**
     * Sets the artifact registry for this tool.
     * Called by the thinker before tool execution.
     *
     * @param registry the conversation's artifact registry
     */
    void setArtifactRegistry(ArtifactRegistry registry);

    /**
     * Gets the artifact registry.
     *
     * @return the artifact registry, or null if not set
     */
    ArtifactRegistry getArtifactRegistry();
}
