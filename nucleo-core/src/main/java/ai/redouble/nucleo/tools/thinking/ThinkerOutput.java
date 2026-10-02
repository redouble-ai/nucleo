/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.annotation.*;

import java.util.*;

/**
 * Base class for thinker outputs that preserves artifacts.
 *
 * <p>All thinker response types should extend this class to ensure artifacts
 * are properly preserved through the agent hierarchy.
 *
 * <p>When a thinker completes, it populates the artifacts field with actual
 * Java objects resolved from its conversation's artifact registry based on
 * artifact_refs and text mentions.
 *
 * @param <R> the type of reasoning for this response
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-21)
 */
public abstract class ThinkerOutput<R extends Reasoning> extends ArtifactResponse<R> {
    @JsonIgnore  // Artifacts are resolved from registry, not deserialized from LLM JSON
    private Set<Artifact> artifacts;

    public ThinkerOutput() {
        super();
        this.artifacts = new LinkedHashSet<>();
    }

    @JsonIgnore
    public Set<Artifact> getArtifacts() {
        return artifacts;
    }

    @JsonIgnore
    public void setArtifacts(Set<Artifact> artifacts) {
        this.artifacts = artifacts;
    }

    public void addArtifact(Artifact artifact) {
        if (artifact != null) {
            this.artifacts.add(artifact);
        }
    }

    public void addAllArtifacts(Collection<? extends Artifact> artifactsToAdd) {
        if (artifactsToAdd != null) {
            this.artifacts.addAll(artifactsToAdd);
        }
    }

    public boolean hasArtifacts() {
        return artifacts != null && !artifacts.isEmpty();
    }
}