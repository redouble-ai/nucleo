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
 * artifact_refs, which holds the references the answer declared and those it
 * wrote in its text fields. A reference that names no artifact of the registry is
 * refused at the reply gate, and the model is asked again.
 *
 * <p>The output of a thinker that ran as another thinker's tool is written for the calling
 * model with the selected objects in the place of {@code artifact_refs}. The calling model
 * reads the same references, and the write registers each object with the calling
 * conversation, so a reference a called thinker returns is one the caller's artifact tools
 * find and the caller's own answer can select.
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

    /**
     * Takes from the registry the artifacts this answer selects, which are the references in
     * {@code artifact_refs}. For a parsed answer that list already holds the references the
     * model wrote in its text fields beside the ones it declared: the parse gate
     * ({@link ArtifactResponse#canonicalizeArtifactRefs}) put them there, so nothing is read
     * from the text here. A reference the registry does not hold selects nothing and is
     * described in {@code violations}, one line each, addressed to the model that wrote it:
     * the reply gate ({@link ThinkingResponseHandler#heldArtifacts}) reports those lines with
     * the reply's other validation errors, so the model is asked again and an answer leaves
     * the thinking loop only when every reference it names is an artifact. The references
     * are canonical after the parse gate; they are normalized again here because an output
     * can also be built programmatically, off the thinking path, and such an output selects
     * what its list names.
     *
     * @param registry the registry of the conversation the answer was given in
     * @param violations receives one line per reference the registry does not hold
     */
    public void resolveArtifacts(ArtifactRegistry registry, List<String> violations) {
        Set<String> refsToResolve = new LinkedHashSet<>();
        if (hasArtifactRefs()) {
            for (String ref : getArtifactRefs()) {
                refsToResolve.add(ArtifactRegistry.normalizeToKey(ref));
            }
        }
        for (String ref : refsToResolve) {
            Artifact artifact = registry.get(ref);
            if (artifact != null) {
                addArtifact(artifact);
            }
            else {
                violations.add("The reference " + ref + " names no artifact the registry lists: refer to an artifact only by a reference the registry shows");
            }
        }
    }

    /**
     * The objects this answer selected. When the answer is written for a model, as the result
     * of the tool call that ran this thinker, they are written in the place of
     * {@code artifact_refs}, each as its reference, and that write registers each one with
     * the conversation of the model that reads it.
     */
    @Override
    public Collection<Artifact> selectedArtifacts() {
        return artifacts;
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