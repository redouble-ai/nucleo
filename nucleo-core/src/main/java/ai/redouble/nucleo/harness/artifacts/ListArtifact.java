/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts;

import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.annotation.*;

import java.util.*;

/**
 * Artifact holding an ordered, homogeneous list of artifacts - its iterands.
 *
 * <p>This is the carrier type for bulk data flowing through the platform: ingesters
 * produce it, fan-out processors consume and produce it, transform tools derive new
 * ones from it. The LLM only ever sees a list artifact as a digest (type, count,
 * sample) or as a {@code {"@ref": ...}} - never as a recursively rendered array.
 * That bounded prompt-form is what decouples context cost from list size; see the
 * {@code ListArtifact} handling in {@link NucleoJsonSerializer}.
 *
 * <p>Iterands are individually addressable: {@link ArtifactRegistry} indexes every
 * iterand under its own ref when the list is registered, so downstream tools can
 * resolve per-iterand refs without the iterands appearing as top-level registry
 * entries.
 *
 * <p><b>Frozen after registration:</b> mutating {@link #getIterands()} after the
 * list has been registered leaves the registry's iterand index stale. Build the
 * full list first, then register. Deriving a subset or reordering means creating a
 * new ListArtifact.
 *
 * @param <T> the iterand artifact type
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-09)
 */
@TypeAlias("list")
public class ListArtifact<T extends Artifact> extends AbstractArtifact {
    @LLMDescription("Ordered iterands of this list artifact")
    @JsonDeserialize(using = ArtifactListDeserializer.class)
    private List<T> iterands;
    @LLMDescription("Type alias of the iterand artifacts, null when the list is empty")
    private String iterandTypeAlias;
    @LLMDescription("Ref of the list this one was derived from, null for source lists")
    private String derivedFromRef;
    @LLMDescription("Worker tool whose fan-out produced this list, null for source lists")
    private String workerToolName;
    @LLMDescription("Instruction every worker received when this list was produced, null for source lists")
    private String instruction;

    public List<T> getIterands() {
        return iterands;
    }

    public void setIterands(List<T> iterands) {
        this.iterands = iterands;
    }

    public String getIterandTypeAlias() {
        return iterandTypeAlias;
    }

    public void setIterandTypeAlias(String iterandTypeAlias) {
        this.iterandTypeAlias = iterandTypeAlias;
    }

    public String getDerivedFromRef() {
        return derivedFromRef;
    }

    public void setDerivedFromRef(String derivedFromRef) {
        this.derivedFromRef = derivedFromRef;
    }

    public String getWorkerToolName() {
        return workerToolName;
    }

    public void setWorkerToolName(String workerToolName) {
        this.workerToolName = workerToolName;
    }

    public String getInstruction() {
        return instruction;
    }

    public void setInstruction(String instruction) {
        this.instruction = instruction;
    }
}
