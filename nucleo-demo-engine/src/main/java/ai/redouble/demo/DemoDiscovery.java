/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.models.discovery.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * The demo's discovery command: {@link CatalogDiscovery} with the demo's own file as the one it
 * writes, and the grades ordered when that file is created. The file is the one {@code --out}
 * names, else the one {@code -Dnucleo.models} names, else the demo's home
 * ({@link DemoHome#catalogFile()}), so a first run in a clone with no catalog writes the
 * module's {@code src/main/resources/models.json} with nothing to pass. When the file did not
 * exist before the run, {@link GradeOrderRace} orders the grades in it and its outcome is
 * printed after the discovery's report; a file that existed keeps its orders.
 * {@code --report-only} writes nothing and orders nothing.
 *
 * <p>The command seals the discovery's permitting envelope and starts the dispatcher itself,
 * as {@link CatalogDiscovery#main} does, and the race runs on that same dispatcher before it
 * is shut down.
 *
 * @author Andrey Santrosyan
 * @since 0.1.5 (2026-11-23)
 */
public final class DemoDiscovery {
    private DemoDiscovery() {}

    /** The user the ordering race's workflow belongs to: a command line has no signed-in person. */
    static final String OWNER = "anonymous";

    /**
     * @param catalog the demo's catalog, whose file takes the orders
     * @param args    the discovery's own arguments
     */
    public static void run(DemoCatalog catalog, String[] args) throws IOException {
        List<String> arguments = new ArrayList<>(List.of(args));
        boolean reportOnly = arguments.contains("--report-only");
        int named = arguments.indexOf("--out");
        Path out = named >= 0 && named + 1 < arguments.size() ? Path.of(arguments.get(named + 1)) : JsonModelsBackend.namedFile();
        if (out == null && !reportOnly && DemoHome.catalogFile() != null) {
            out = DemoHome.catalogFile();
            arguments.add("--out");
            arguments.add(out.toString());
        }
        boolean first = !reportOnly && out != null && !Files.isRegularFile(out);
        JobDispatcher dispatcher = JobDispatcher.getInstance();
        dispatcher.sealComplianceEnvelope(CatalogDiscovery.permitting());
        dispatcher.start();
        try {
            CatalogDiscovery.Result result = CatalogDiscovery.execute(arguments.toArray(String[]::new));
            if (first && Files.isRegularFile(out)) {
                DemoHome.name(out);
                System.out.println(GradeOrderRace.describe(GradeOrderRace.run(catalog, OWNER, result.classified())));
            }
        }
        finally {
            dispatcher.shutdown(JobDispatcher.DEFAULT_SHUTDOWN_TIMEOUT_MS);
        }
    }
}
