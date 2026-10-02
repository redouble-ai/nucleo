/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.quarkus;

import ai.redouble.nucleo.harness.admission.*;
import io.quarkus.runtime.*;
import jakarta.annotation.*;
import jakarta.enterprise.context.*;
import jakarta.enterprise.event.*;
import org.slf4j.*;

/**
 * Registers the application's database with the runtime when the container starts: the
 * {@link CountingDBResourceProvider} that {@link DBResourceProviders#registerDefault()} builds
 * from {@link DatabaseSettings}, bound from {@code nucleo.database.*}. Without
 * {@code nucleo.database.max-concurrent} nothing is registered. The counter needs nothing from
 * the container: jobs declare it, wait for a permit, and then reach the database however the
 * application does - Panache, an injected {@code EntityManager}, a {@code @Transactional} bean,
 * an Agroal {@code DataSource}.
 *
 * <p>This is the Quarkus half of what the Spring starter's {@code NucleoDatabase} does. It
 * observes {@link StartupEvent} after {@link NucleoRuntime}, which binds the properties first.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-22)
 */
@ApplicationScoped
public class NucleoDatabase {
    private static final Logger log = LoggerFactory.getLogger(NucleoDatabase.class);
    private volatile CountingDBResourceProvider provider;

    void onStart(@Observes @Priority(2) StartupEvent event) {
        provider = DBResourceProviders.registerDefault();
        if (provider == null) {
            log.info("No database provider registered: nucleo.database.max-concurrent is not set");
            return;
        }
        log.info("Database provider '{}' registered, {} jobs at once", provider.name(), ((DatabaseGate) provider.admission()).capacity());
    }

    @PreDestroy
    void stop() {
        if (provider != null) {
            DBResourceProviders.unregister(provider);
        }
    }

    /** The registered provider, null when no bound was set. */
    public CountingDBResourceProvider provider() {
        return provider;
    }
}
