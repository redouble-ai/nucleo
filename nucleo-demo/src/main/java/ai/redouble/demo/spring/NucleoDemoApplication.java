/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.spring;

import ai.redouble.demo.*;
import ai.redouble.demo.extract.*;
import ai.redouble.nucleo.spring.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.context.event.*;
import org.springframework.context.*;
import org.springframework.context.annotation.*;

import java.io.*;

/**
 * A Spring Boot process running the runtime in-process, through the starter and nothing
 * else: credentials are Boot properties under {@code nucleo.credentials.*}, bound in
 * {@code application.yaml}; the dispatcher's lifecycle is the starter's {@code NucleoRuntime}
 * bean; everything else runs on the shipped defaults, AWS through the AWS SDK's own
 * resolution, and each grade served from the first catalog entry a configured provider can
 * call. {@code discover} as the first argument runs the catalog discovery instead of the web
 * server and exits, which the starter's entry point provides. The demo itself - the agent,
 * the workflows, the index, the corpus, the page - is {@code nucleo-demo-engine}, a module
 * with no web framework in it; the beans below are this host handing the engine to Spring.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-11)
 */
@SpringBootApplication
public class NucleoDemoApplication {
    /** The discovery command as a person runs it from this module's directory, after building the jar. */
    static final String DISCOVER_COMMAND = "java -jar target/nucleo-demo.jar discover 2>&1 | tee discovery.txt";

    public static void main(String[] args) throws IOException {
        // the demo's catalog is this module's src/main/resources/models.json: named to the runtime
        // before anything reads a catalog, so the web run and the discovery command alike read and
        // write it
        DemoHome.configure(NucleoDemoApplication.class, "application.yaml");
        NucleoSpringApplication.run(NucleoDemoApplication.class, args);
    }

    @Bean
    public FileIndex fileIndex() {
        return new FileIndex();
    }

    @Bean
    public DemoCorpus demoCorpus() {
        return new DemoCorpus();
    }

    @Bean
    public DemoCatalog demoCatalog() {
        return new DemoCatalog("nucleo-demo", DISCOVER_COMMAND);
    }

    /** The shared write-side catalog logic, over this host's session credential store. */
    @Bean
    public CatalogAdmin catalogAdmin(DemoCatalog catalog, DemoCorpus corpus, SessionCredentials session) {
        return new CatalogAdmin(catalog, corpus, session);
    }

    /**
     * Once the application is up: the catalog it runs on, and the steps to fix it when it is
     * not this account's. Quiet under the {@code discover} command, which is the fix running.
     */
    @Bean
    public ApplicationListener<ApplicationReadyEvent> catalogReport(DemoCatalog catalog, NucleoRuntime runtime) {
        return event -> {
            if (runtime.isAutoStartup()) {
                catalog.logReport();
            }
        };
    }
}
