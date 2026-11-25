/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts;

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
 * <p>An artifact does not change once it is registered. A model is shown the summary of a
 * long field, made once and kept, and reads the whole value through the artifact tools; a
 * value changed after registration would no longer be the one its summary describes.
 *
 * <p>An artifact is the only class a field may carry {@code @LLMSummarizable} in: the
 * summary of a field is kept here, and the reference is what the whole value is read by.
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
     * The summaries kept for this artifact's long fields. The serializer makes each once
     * and shows it in every later write; a conversation store saves them with the artifact.
     * One instance for the life of the artifact, never null, and no part of the artifact's
     * JSON or of its schema: an implementation marks the getter {@code @JsonIgnore}.
     *
     * @return this artifact's kept summaries
     */
    ArtifactSummaries getSummaries();

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
