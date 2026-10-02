/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models.discovery;

import ai.redouble.nucleo.harness.models.*;

import java.util.*;

/**
 * One model as a provider's account lists it, with whatever facts the listing carried. The
 * wire id is what the provider would accept on a call; every other field is null when the
 * listing does not say - a provider never invents a fact the API did not state, and the
 * catalog discovery fills the gaps from the seed or reports them.
 *
 * <p>The modalities are the provider's own words, upper-case as it spells them ({@code TEXT},
 * {@code IMAGE}, {@code VIDEO}, {@code SPEECH}, {@code EMBEDDING}, ...), kept verbatim because
 * the runtime asks only two questions of them: {@link #textSeat()} - text in and text out, the
 * shape every LLM client speaks - and {@link #embeddingsOutput()}. A model that answers neither
 * ({@link #otherModality()}) is a fact about the account with no seat in the runtime: the
 * discovery writes it as a seatless entry, and no picker ever lands on it.
 *
 * @param wireModelId      the id the provider accepts on the wire, as listed
 * @param inputModalities  what the model takes, as the listing spells it, null when it does not say
 * @param outputModalities what the model produces, as the listing spells it, null when it does not say
 * @param supportsVision   whether the listing declares image input, null when it does not say
 * @param onDemand         whether the id can be invoked as listed (on-demand, or an inference profile), null when the listing does not say; false is an id that needs provisioned throughput or a profile the listing names separately
 * @param retired          whether the provider marks the model as legacy or deprecated, null when it does not say
 * @param requiresLax      whether the model cannot run at zero retention (its allowed retention modes exclude {@code none}), null when the surface does not expose retention modes
 * @param tpm              the account's tokens-per-minute quota for this endpoint, null when the provider publishes none through an API
 * @param rpm              the account's requests-per-minute quota for this endpoint, null when the provider publishes none through an API
 * @param note             anything else the listing said worth showing a person (a lifecycle status, a display name), or null
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-12)
 */
public record DiscoveredModel(String wireModelId, List<String> inputModalities, List<String> outputModalities, Boolean supportsVision,
                              Boolean onDemand, Boolean retired, Boolean requiresLax, Integer tpm, Integer rpm, String note) {
    /** The modality word every LLM client speaks, as providers spell it. */
    public static final String TEXT = "TEXT";
    /** The modality word of an embeddings model's output, as providers spell it. */
    public static final String EMBEDDING = "EMBEDDING";

    public DiscoveredModel {
        if (wireModelId == null || wireModelId.isBlank()) {
            throw new IllegalArgumentException("a discovered model needs its wire id");
        }
        inputModalities = inputModalities != null ? List.copyOf(inputModalities) : null;
        outputModalities = outputModalities != null ? List.copyOf(outputModalities) : null;
    }

    /** A listing that carried nothing but the id. */
    public static DiscoveredModel of(String wireModelId) {
        return new DiscoveredModel(wireModelId, null, null, null, null, null, null, null, null, null);
    }

    /** A listing that carried the id and a note. */
    public static DiscoveredModel of(String wireModelId, String note) {
        return new DiscoveredModel(wireModelId, null, null, null, null, null, null, null, null, note);
    }

    /**
     * Whether the model takes text and produces text, the shape every LLM client speaks; null
     * when the listing named no modalities, which the discovery reads as an LLM listing.
     */
    public Boolean textSeat() {
        if (inputModalities == null || outputModalities == null) {
            return null;
        }
        return inputModalities.contains(TEXT) && outputModalities.contains(TEXT);
    }

    /** Whether the model produces embeddings; null when the listing named no output modalities. */
    public Boolean embeddingsOutput() {
        return outputModalities != null ? outputModalities.contains(EMBEDDING) : null;
    }

    /**
     * Whether the listing names modalities and they fit neither an LLM seat nor an embeddings
     * one: an image, video or speech model the account offers and the runtime has no client for.
     */
    public boolean otherModality() {
        return Boolean.FALSE.equals(textSeat()) && Boolean.FALSE.equals(embeddingsOutput());
    }
}
