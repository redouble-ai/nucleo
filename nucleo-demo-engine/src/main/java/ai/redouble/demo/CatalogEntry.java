/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

import ai.redouble.nucleo.harness.models.*;

import java.util.*;

/**
 * One catalog entry on the status panel: its id, grade and provider, which client family it
 * belongs to (an LLM, an embeddings model or a decision model), whether it takes a seat,
 * whether the compliance envelope permits it, its status, what it accepts beyond text (images,
 * documents), its output modalities, its per-million prices in the entry's
 * currency, the bound it declares: a quota window ({@code tpm}) or, for a decision model
 * served from the deployment's own machine, how many requests its server takes at once
 * ({@code maxConcurrent}), whether its grade and prices are the discovery's inference no
 * person has confirmed ({@code unverified}), and its note, the discovery's own words on where
 * it came from.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public record CatalogEntry(String id, Grade grade, String provider, ModelKind kind, boolean seat, boolean permitted,
                           ModelStatus status, boolean vision, boolean documents, List<String> outputModalities, Double inputPricePerMillion,
                           Double outputPricePerMillion, String currency, Integer tpm, Integer maxConcurrent, boolean unverified, String note) {}
