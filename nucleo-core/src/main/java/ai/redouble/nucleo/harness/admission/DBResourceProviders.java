/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;

import ai.redouble.nucleo.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * The process's database providers, by {@link DBResourceProvider#name()}. A tool is built by
 * reflection from its parent alone, so it cannot be handed the host's provider; it names the
 * datasource instead, the way code names a JNDI resource, and declares what this registry
 * answers:
 *
 * <pre>
 * JdbcResourceProvider db = DBResourceProviders.get("default", JdbcResourceProvider.class);
 * req.addProvider(db);
 * </pre>
 *
 * <p>The host registers each provider once at startup. A name is one datasource: a second
 * provider under a registered name is refused rather than replacing the first, because jobs
 * already admitted against the first hold its permits.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-22)
 */
public final class DBResourceProviders {
    private static final ConcurrentHashMap<String, DBResourceProvider<?>> PROVIDERS = new ConcurrentHashMap<>();

    private DBResourceProviders() {
    }

    /**
     * Registers a provider under its name. Registering the same instance again is a no-op.
     *
     * @throws IllegalStateException when another provider already holds the name
     */
    public static void register(DBResourceProvider<?> provider) {
        DBResourceProvider<?> existing = PROVIDERS.putIfAbsent(provider.name(), provider);
        if (existing != null && existing != provider) {
            throw new IllegalStateException("A database provider named '" + provider.name() + "' is already registered ("
                    + existing.getClass().getName() + "); a name is one datasource");
        }
    }

    /**
     * Removes the provider when it is the one registered under its name, for a host whose
     * context shuts down while the process lives on.
     */
    public static void unregister(DBResourceProvider<?> provider) {
        PROVIDERS.remove(provider.name(), provider);
    }

    /**
     * The provider registered under the name, as the type the caller declares.
     *
     * @throws IllegalStateException when no provider holds the name, naming the registered
     *                               ones, or when the one registered is not of that type
     */
    public static <P extends DBResourceProvider<?>> P get(String name, Class<P> type) {
        DBResourceProvider<?> provider = PROVIDERS.get(name);
        if (provider == null) {
            throw new IllegalStateException("No database provider named '" + name + "' is registered; registered: "
                    + new TreeSet<>(PROVIDERS.keySet()));
        }
        if (!type.isInstance(provider)) {
            throw new IllegalStateException("The database provider named '" + name + "' is a " + provider.getClass().getName()
                    + ", not a " + type.getName());
        }
        return type.cast(provider);
    }

    /**
     * Registers the default provider from {@link DatabaseSettings}: a
     * {@link CountingDBResourceProvider} under {@code name} with {@code maxConcurrent} permits.
     * A host calls this once at startup, after its properties are bound; the Spring Boot
     * starter and the Quarkus integration do.
     *
     * @return the provider registered, or null when {@code maxConcurrent} is unset and nothing
     *         was registered
     * @throws IllegalStateException when another provider already holds the name
     */
    public static CountingDBResourceProvider registerDefault() {
        DatabaseSettings settings = Settings.get(DatabaseSettings.class);
        if (settings.maxConcurrent == null) {
            return null;
        }
        CountingDBResourceProvider provider = new CountingDBResourceProvider(settings.name, settings.maxConcurrent);
        register(provider);
        return provider;
    }

    /** Every registered name, sorted. */
    public static SortedSet<String> names() {
        return new TreeSet<>(PROVIDERS.keySet());
    }
}
