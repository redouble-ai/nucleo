/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.quarkus;

import ai.redouble.demo.*;
import io.quarkus.runtime.*;
import jakarta.enterprise.context.*;
import jakarta.enterprise.event.*;

/**
 * Once the container is up: the catalog the process runs on, and the steps to fix it when it
 * is not this account's, logged the same way the Spring host logs it on its ready event. The
 * dispatcher is already started by the integration module's runtime bean at this point.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-22)
 */
@ApplicationScoped
public class DemoStartup {
    private final DemoCatalog catalog;

    public DemoStartup(DemoCatalog catalog) {
        this.catalog = catalog;
    }

    void onStart(@Observes StartupEvent event) {
        // name the demo's catalog file, this module's src/main/resources/models.json, to the
        // runtime and seed it on first run, before the report reads what the runtime loaded
        DemoHome.configure(DemoStartup.class, "application.properties");
        catalog.logReport();
    }
}
