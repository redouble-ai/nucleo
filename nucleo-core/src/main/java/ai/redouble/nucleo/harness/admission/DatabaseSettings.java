/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;

import ai.redouble.nucleo.*;

/**
 * The default database provider's knobs: whether the host registers a
 * {@link CountingDBResourceProvider}, under what name and with what bound. Bound from
 * {@code nucleo.database.*} in a Spring Boot or Quarkus host, or assigned by a configurator;
 * {@link DBResourceProviders#registerDefault()} reads them.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-22)
 */
public class DatabaseSettings extends Settings {

    /**
     * The provider's name: the health row {@code db:<name>} and the name jobs look it up by in
     * {@link DBResourceProviders}.
     */
    public volatile String name = "default";

    /**
     * How many jobs may use a connection of the application's datasource at once: the bound of
     * the counting provider. It must leave the pool room for everything else that uses it. Null
     * registers no provider: the pool's size is the host's, and no bound is inferred from it.
     */
    public volatile Integer maxConcurrent;
}
