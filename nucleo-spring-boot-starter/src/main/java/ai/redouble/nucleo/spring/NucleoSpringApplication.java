/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.spring;

import ai.redouble.nucleo.harness.models.discovery.*;
import org.springframework.boot.*;
import org.springframework.boot.builder.*;
import org.springframework.context.*;

import java.io.*;
import java.util.*;

/**
 * The entry point of a Spring Boot application that runs the runtime: what
 * {@link SpringApplication#run} is, plus one command. {@code discover} as the first
 * argument starts the context without the web server, on the same credentials bound the
 * same way, runs the catalog discovery with the remaining arguments and exits with its code:
 * {@code java -jar app.jar discover --out src/main/resources/models.json}. It writes the file
 * {@code --out} names, else the one {@code -Dnucleo.models} names; written into
 * {@code src/main/resources}, the build carries it onto the classpath, where the runtime finds it
 * the way logging configuration is found - wherever and however the application is then launched.
 * Any other arguments start the application as {@code SpringApplication.run} would.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-15)
 */
public final class NucleoSpringApplication {
    /** The first argument that runs the discovery instead of the application. */
    public static final String DISCOVER = "discover";

    private NucleoSpringApplication() {}

    public static ConfigurableApplicationContext run(Class<?> source, String... args) throws IOException {
        if (args.length > 0 && args[0].equals(DISCOVER)) {
            // the report is the output; the runtime's INFO lines (every ping's wire dump) are not,
            // and a system property outranks the level application.yaml sets for the web run
            System.setProperty("logging.level.ai.redouble", "WARN");
            ConfigurableApplicationContext context = new SpringApplicationBuilder(source).web(WebApplicationType.NONE).run(args);
            CatalogDiscovery.main(Arrays.copyOfRange(args, 1, args.length));
            System.exit(SpringApplication.exit(context));
        }
        return SpringApplication.run(source, args);
    }
}
