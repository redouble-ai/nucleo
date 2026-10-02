/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm;

import ai.redouble.nucleo.harness.models.*;

/**
 * Shared {@link ClientProvider} template: construct the provider-specific client, bind the
 * spec, return. The provider object itself holds no resources and reads no secrets - those
 * live in the client constructor, built on demand by {@link #newClient()} - so providers are
 * cheap to instantiate at registration while client construction stays lazy.
 *
 * @param <E> the exact client type this provider builds
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-21)
 */
public abstract class AbstractClientProvider<E extends Client> implements ClientProvider<E> {
    /** Builds a fresh, unconfigured client of this provider's type. */
    protected abstract E newClient();

    @Override
    public E createClient(ModelSpec spec) {
        E client = newClient();
        client.setModel(spec);
        return client;
    }
}
