/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts;

import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.annotation.*;

/**
 * Base class for all artifact entities that can be preserved across agent workflows.
 *
 * <p>Provides the artifactRef field that uniquely identifies this artifact within
 * the agent execution context. The reference format is {@code «artifact:alias~uuid»} where:
 * <ul>
 *   <li>alias is the hierarchical {@code @TypeAlias} value (colons separate IS-A levels,
 *       e.g. "link:cite:pubmed" means PubMedArticle IS-A CitationArtifact IS-A LinkArtifact)</li>
 *   <li>uuid is a 6-character alphanumeric identifier</li>
 * </ul>
 *
 * <p>Holds the artifact's {@link ArtifactSummaries}: the serializer makes the summary of a
 * long field once and every later write shows it. The full value stays in the field, which
 * is where the artifact tools read it.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-13)
 */
public abstract class AbstractArtifact implements Artifact {
    // "@ref" is the key an artifact is shown to a model under, so a model referring to one in a
    // tool call's input may write it back the same way
    @JsonAlias(NucleoJsonSerializer.ARTIFACT_REF_AS_SHOWN)
    @LLMDescription("Unique reference identifier for this artifact in format artifact:type~uuid")
    private String artifactRef;

    /**
     * The kept summaries, no part of the artifact's JSON. Made with the artifact: an
     * artifact conveyed across jobs is serialized by two threads at once, and both must
     * meet the same instance.
     */
    @JsonIgnore
    private final transient ArtifactSummaries summaries = new ArtifactSummaries();

    @Override
    public String getArtifactRef() {
        return artifactRef;
    }

    @Override
    public void setArtifactRef(String artifactRef) {
        this.artifactRef = artifactRef;
    }

    /**
     * Checks if this artifact has been assigned a reference.
     *
     * @return true if artifactRef is set, false otherwise
     */
    public boolean hasReference() {
        return artifactRef != null && !artifactRef.trim().isEmpty();
    }

    @Override
    @JsonIgnore
    public ArtifactSummaries getSummaries() {
        return summaries;
    }

    @Override
    public String toString() {
        if (hasReference()) {
            return artifactRef;
        }
        return super.toString();
    }
}
