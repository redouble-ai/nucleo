/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import java.util.*;

/**
 * Source of catalog {@link ModelSpec}s behind the {@link Models} facade. Implementations
 * load specs from JSON, code, a database, or a composition; the default is
 * {@link JsonModelsBackend}, selected via {@code ModelSettings.backendClass}. A backend is
 * the immutable DEFAULTS layer - runtime mutation (learned limits) lives in the facade's
 * override layer, never written back here.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-21)
 */
public interface ModelsBackend {
    /**
     * Resolves a spec by its canonical {@link ModelSpec#getId() id} or its
     * {@link ModelSpec#getWireModelId() wire id} (the alias the billing path holds).
     * Returns null if neither matches.
     */
    ModelSpec spec(String id);

    /** All catalog specs. */
    Collection<ModelSpec> all();

    /**
     * The deployment's {@link CatalogPins} when the catalog source carries them, else null.
     * A backend that loads from a source without a pins notion returns null and the default
     * picker falls to its configured-entry rule for every grade.
     */
    CatalogPins pins();
}
