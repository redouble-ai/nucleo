/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts;

import ai.redouble.nucleo.harness.schema.*;

/**
 * Marker interface for domain entities that can be preserved as artifacts
 * across multi-agent workflows.
 *
 * <p>When an entity implements Artifact, the framework will:
 * <ul>
 *   <li>Automatically detect it during serialization to LLM</li>
 *   <li>Generate a unique artifact reference (e.g., «artifact:link:cite~a1b2c3»)</li>
 *   <li>Store the full object in the agent's ephemeral registry</li>
 *   <li>Replace the object with its reference in LLM communications</li>
 *   <li>Allow agents to reference the artifact without regurgitating details</li>
 * </ul>
 *
 * <p>This solves the "broken telephone" problem in multi-agent hierarchies where
 * domain objects get corrupted as they pass through multiple LLM transformations.
 *
 * <p>Concrete implementations should extend {@link AbstractArtifact} which provides
 * the artifactRef field and associated logic.
 *
 * @see AbstractArtifact
 * @see ArtifactResponse
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-13)
 */
public interface Artifact  {

    /**
     * Gets a previously cached summary for a field, or null if not cached.
     * Used by the Jackson serializer to avoid re-summarizing on repeated serializations.
     *
     * @param fieldName the snake_case field name
     * @return the cached SummarizedField, or null
     */
    SummarizedField getCachedSummary(String fieldName);

    /**
     * Caches a summary for a field. Called by the Jackson serializer after generating
     * a summary so subsequent serializations skip the Summarizer.
     *
     * @param fieldName the snake_case field name
     * @param value the SummarizedField containing both full text and summary
     */
    void cacheSummary(String fieldName, SummarizedField value);

    /**
     * Gets the artifact reference string, or null if not yet assigned.
     *
     * @return the artifact reference in the canonical format «artifact:type~uuid», or null
     */
    String getArtifactRef();

    /**
     * Sets the artifact reference string.
     *
     * @param ref the artifact reference
     */
    void setArtifactRef(String ref);
}