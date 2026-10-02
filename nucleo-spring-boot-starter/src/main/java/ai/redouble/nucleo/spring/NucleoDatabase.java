/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.spring;

import ai.redouble.nucleo.harness.admission.*;
import org.slf4j.*;
import org.springframework.beans.factory.*;

/**
 * Registers the application's database with the runtime once every bean exists: the
 * {@link CountingDBResourceProvider} that {@link DBResourceProviders#registerDefault()} builds
 * from {@link DatabaseSettings}, bound from {@code nucleo.database.*}. Without
 * {@code nucleo.database.max-concurrent} nothing is registered. The counter needs nothing from
 * the context: jobs declare it, wait for a permit, and then reach the database however the
 * application does - a repository, a {@code JdbcTemplate}, an {@code @Transactional} service.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-22)
 */
public class NucleoDatabase implements SmartInitializingSingleton, DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(NucleoDatabase.class);
    private volatile CountingDBResourceProvider provider;

    @Override
    public void afterSingletonsInstantiated() {
        provider = DBResourceProviders.registerDefault();
        if (provider == null) {
            log.info("No database provider registered: nucleo.database.max-concurrent is not set");
            return;
        }
        log.info("Database provider '{}' registered, {} jobs at once", provider.name(), ((DatabaseGate) provider.admission()).capacity());
    }

    @Override
    public void destroy() {
        if (provider != null) {
            DBResourceProviders.unregister(provider);
        }
    }

    /** The registered provider, null when no bound was set. */
    public CountingDBResourceProvider provider() {
        return provider;
    }
}
