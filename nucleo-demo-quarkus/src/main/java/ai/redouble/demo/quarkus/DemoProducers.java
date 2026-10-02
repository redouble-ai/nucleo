/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.quarkus;

import ai.redouble.demo.*;
import ai.redouble.demo.extract.*;
import jakarta.enterprise.context.*;
import jakarta.enterprise.inject.*;

/**
 * The engine's plain objects handed to the CDI container: the same {@link FileIndex},
 * {@link DemoCorpus} and {@link DemoCatalog} the Spring host wires as beans, produced here as
 * application-scoped singletons so the JAX-RS resources inject them. The engine knows nothing
 * of either container; each host supplies these one way it understands.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-22)
 */
@ApplicationScoped
public class DemoProducers {
    @Produces
    @ApplicationScoped
    public FileIndex fileIndex() {
        return new FileIndex();
    }

    @Produces
    @ApplicationScoped
    public DemoCorpus demoCorpus() {
        return new DemoCorpus();
    }

    @Produces
    @ApplicationScoped
    public DemoCatalog demoCatalog() {
        // no discovery command: this host runs the discovery from its page's Discover button only
        return new DemoCatalog("nucleo-demo-quarkus", null);
    }

    /** The shared write-side catalog logic, over this host's session credential store. */
    @Produces
    @ApplicationScoped
    public CatalogAdmin catalogAdmin(DemoCatalog catalog, DemoCorpus corpus, SessionCredentials session) {
        return new CatalogAdmin(catalog, corpus, session);
    }
}
