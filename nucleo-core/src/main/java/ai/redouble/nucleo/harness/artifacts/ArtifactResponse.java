/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts;

import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.annotation.*;

import java.util.*;

/**
 * Base class for agent responses that can reference preserved artifacts.
 *
 * <p>Extends ReasonablePojo to include reasoning capabilities, and adds the
 * artifactRefs field for tracking which artifacts should be propagated to
 * parent contexts.
 *
 * <p>The refs are canonical by construction: the model's parsed list enters through a
 * package-private Jackson setter and {@link #canonicalizeArtifactRefs} replaces it with
 * the normalized, deduplicated form right after parse, while programmatic additions go
 * through {@link #addArtifactRef}, which normalizes each ref - so no caller outside this
 * package can plant a variant spelling.
 *
 * <p>The artifactRefs field is curated propagation: the responder decides what its
 * parent needs.
 * <ul>
 *   <li>Include ALL artifact references the parent needs to understand the response</li>
 *   <li>Can filter out irrelevant artifacts from child tools</li>
 *   <li>Can aggregate artifacts from multiple sources</li>
 *   <li>Acts as the single source of truth for what propagates upward</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>
 * public class AnalysisResponse extends ArtifactResponse&lt;ChainOfThoughtReasoning&gt; {
 *     private String analysis;
 *     // artifactRefs inherited - contains refs in canonical format: «artifact:link:cite~a1b2c3»
 * }
 * </pre>
 *
 * @param <R> the type of reasoning for this response
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-13)
 */
public abstract class ArtifactResponse<R extends Reasoning> extends ReasonablePojo<R> {
    @LLMDescription("Artifact references to propagate to parent context. " +
                    "Use the full reference as shown in the registry, e.g. \u00ABartifact:link:cite~abc123\u00BB. " +
                    "Include ALL artifacts the parent needs.")
    private List<String> artifactRefs;

    /**
     * Creates a new ArtifactResponse with an empty artifactRefs list.
     */
    public ArtifactResponse() {
        super();
        this.artifactRefs = new ArrayList<>();
    }

    /**
     * Gets the list of artifact references to propagate to the parent context.
     * References are in canonical format: «artifact:type~uuid».
     *
     * @return list of artifact references in canonical format
     */
    public List<String> getArtifactRefs() {
        return artifactRefs;
    }

    /**
     * Jackson's door for the model's parsed answer - package-private so nothing outside
     * this package can plant an unnormalized ref. The response handler runs
     * {@link #canonicalizeArtifactRefs} right after every parse, which replaces whatever
     * arrived here with its canonical form; programmatic callers add through
     * {@link #addArtifactRef}, which normalizes on the way in.
     *
     * @param artifactRefs the model's declared references, exactly as written
     */
    @JsonProperty
    void setArtifactRefs(List<String> artifactRefs) {
        this.artifactRefs = artifactRefs;
    }

    /**
     * The single canonicalization gate for a parsed response: normalizes every declared
     * reference, unions the {@code «artifact:...»} mentions found in the answer's text
     * ({@link ArtifactRegistry#refsMentionedIn}), deduplicates across spellings, and
     * replaces the list - declared order first, then first-mention order. The response
     * handler runs it after every parse, so a response leaving the thinking loop carries
     * canonical refs by construction and a text-mentioned artifact can never hide behind
     * a declared duplicate.
     */
    public void canonicalizeArtifactRefs(String answerText) {
        Set<String> canonical = new LinkedHashSet<>();
        if (artifactRefs != null) {
            for (String ref : artifactRefs) {
                if (ref != null && !ref.trim().isEmpty()) {
                    canonical.add(ArtifactRegistry.normalizeToKey(ref));
                }
            }
        }
        canonical.addAll(ArtifactRegistry.refsMentionedIn(answerText));
        this.artifactRefs = new ArrayList<>(canonical);
    }

    /**
     * Adds a single artifact reference to the propagation list.
     * The reference is normalized to canonical format: «artifact:type~uuid».
     *
     * @param artifactRef the artifact reference in any format
     */
    public void addArtifactRef(String artifactRef) {
        if (this.artifactRefs == null) {
            this.artifactRefs = new ArrayList<>();
        }
        if (artifactRef != null && !artifactRef.trim().isEmpty()) {
            String normalized = ArtifactRegistry.normalizeToKey(artifactRef);
            if (!this.artifactRefs.contains(normalized)) {
                this.artifactRefs.add(normalized);
            }
        }
    }

    /**
     * Checks if this response references any artifacts.
     *
     * @return true if artifactRefs contains at least one reference
     */
    public boolean hasArtifactRefs() {
        return artifactRefs != null && !artifactRefs.isEmpty();
    }
}