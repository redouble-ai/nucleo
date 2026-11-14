/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.annotation.*;
import org.slf4j.*;

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
    private static final Logger log = LoggerFactory.getLogger(ThinkerOutput.class);
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

    /**
     * Takes from the registry the artifacts this answer selects: the references it declares
     * in {@code artifact_refs} and those written in its text, which is what {@link #toString()}
     * returns. A reference the registry does not hold is logged and skipped. Declared
     * references are canonical after the parse gate; they are normalized again here because
     * an output can also be built programmatically, off the thinking path.
     *
     * @param registry the registry of the conversation the answer was given in
     */
    public void resolveArtifacts(ArtifactRegistry registry) {
        Set<String> refsToResolve = new LinkedHashSet<>();
        if (hasArtifactRefs()) {
            for (String ref : getArtifactRefs()) {
                refsToResolve.add(ArtifactRegistry.normalizeToKey(ref));
            }
        }
        refsToResolve.addAll(ArtifactRegistry.refsMentionedIn(toString()));
        for (String ref : refsToResolve) {
            Artifact artifact = registry.get(ref);
            if (artifact != null) {
                addArtifact(artifact);
            }
            else {
                log.warn("Could not resolve artifact reference: {}", ref);
            }
        }
    }

    /**
     * The resolved artifacts keyed by their canonical reference, in the order they were
     * resolved: the form in which a selection is handed to a reader that looks an artifact
     * up by the reference the answer names.
     */
    public Map<String, Artifact> artifactsByRef() {
        Map<String, Artifact> byRef = new LinkedHashMap<>();
        for (Artifact artifact : artifacts) {
            byRef.put(ArtifactRegistry.normalizeToKey(artifact.getArtifactRef()), artifact);
        }
        return byRef;
    }

    public boolean hasArtifacts() {
        return artifacts != null && !artifacts.isEmpty();
    }
}