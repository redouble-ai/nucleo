/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.spring;

import ai.redouble.nucleo.*;
import ai.redouble.nucleo.harness.admission.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.test.context.runner.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The starter registers the default counting provider exactly when
 * {@code nucleo.database.max-concurrent} is set, under {@code nucleo.database.name}, with
 * nothing else from the context: no datasource, no transaction manager. Contexts start in
 * discover mode, so the dispatcher is never started.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-22)
 */
class NucleoDatabaseTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NucleoAutoConfiguration.class))
            .withBean(ApplicationArguments.class, () -> new DefaultApplicationArguments(NucleoSpringApplication.DISCOVER));
    private DatabaseSettings settings;
    private String nameBefore;
    private Integer boundBefore;

    @BeforeEach
    void remember() {
        settings = Settings.get(DatabaseSettings.class);
        nameBefore = settings.name;
        boundBefore = settings.maxConcurrent;
    }

    @AfterEach
    void restore() {
        settings.name = nameBefore;
        settings.maxConcurrent = boundBefore;
    }

    @Test
    void aBoundRegistersTheCounter_withNothingElseInTheContext() {
        runner.withPropertyValues("nucleo.database.max-concurrent=3", "nucleo.database.name=orders")
                .run(context -> {
                    assertNull(context.getStartupFailure(), "the counter needs no datasource and no transaction manager");
                    CountingDBResourceProvider provider = DBResourceProviders.get("orders", CountingDBResourceProvider.class);
                    assertSame(provider, context.getBean(NucleoDatabase.class).provider());
                    assertEquals(3, ((DatabaseGate) provider.admission()).capacity(), "the bound the property set");
                });
        assertFalse(DBResourceProviders.names().contains("orders"), "a closed context takes its provider back");
    }

    @Test
    void withoutABoundNothingIsRegistered() {
        runner.run(context -> {
            assertNull(context.getBean(NucleoDatabase.class).provider());
            assertFalse(DBResourceProviders.names().contains("default"));
        });
    }
}
