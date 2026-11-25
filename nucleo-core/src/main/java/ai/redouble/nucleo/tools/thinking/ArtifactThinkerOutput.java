/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.annotation.*;

/**
 * Thinker output that is itself an {@link Artifact}: the answer a thinker's model writes
 * becomes an entry of the registry of whoever called the thinker, so the caller's model
 * can read it back whole, hand it on by reference, and be shown its long fields as
 * summaries. It is also what lets a thinker serve as a fan-out worker, its typed answer
 * landing directly in the result list. Single inheritance forces this class to carry its
 * own ref field and summaries instead of extending {@link AbstractArtifact} - the
 * {@link Artifact} interface is the contract, {@code AbstractArtifact} is only a
 * convenience base.
 *
 * <p>This is the one artifact a model writes. Asked for as the answer of a reply, its
 * fields are offered without {@code artifact_ref}, a reference the model writes on it is
 * dropped, and the object receives its reference when the caller registers it, as the
 * result of a tool does. Anywhere else in a reply - in a field of an answer, in the input
 * of a tool call - it is a reference to an artifact the registry already lists, as every
 * artifact is.
 *
 * <p>A field the model generates is as long as the prompt asks for, so an
 * {@code @LLMSummarizable} threshold on one is a guard against a runaway answer and is set
 * generously, well above what the field should hold.
 *
 * <p>Concrete subclasses need a {@code @TypeAlias} annotation (registration mints
 * refs from it) and should keep their answer data in serializable fields: worker
 * outputs must be self-contained, because refs pointing into the worker thinker's
 * own conversation registry are worker-scoped and do not resolve in the parent.
 *
 * @param <R> the reasoning type for this response
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-09)
 */
public abstract class ArtifactThinkerOutput<R extends Reasoning> extends ThinkerOutput<R> implements Artifact {
    // "@ref" is the key an artifact is shown to a model under, so a model referring to one in a
    // tool call's input may write it back the same way
    @JsonAlias(NucleoJsonSerializer.ARTIFACT_REF_AS_SHOWN)
    @LLMDescription("Unique reference identifier for this artifact in format artifact:type~uuid")
    private String artifactRef;

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

    @Override
    @JsonIgnore
    public ArtifactSummaries getSummaries() {
        return summaries;
    }
}
