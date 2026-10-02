/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.spring;

import ai.redouble.nucleo.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.http.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.test.context.runner.*;
import org.springframework.context.annotation.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The auto-configuration puts the credential store, the settings binder and the runtime bean
 * into a context that declares nothing, and steps aside for a host that declares its own.
 * The binder maps {@code nucleo.*} properties onto the registered settings classes - the
 * kebab-cased class-minus-Settings and field name - before any bean builds. Contexts start
 * in discover mode, so the runtime bean is present and not started: the dispatcher is a
 * process-wide singleton and no test starts it.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class NucleoAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NucleoAutoConfiguration.class))
            .withBean(ApplicationArguments.class, () -> new DefaultApplicationArguments(NucleoSpringApplication.DISCOVER));

    @Test
    void theStarterDeclaresTheStoreAndTheRuntime() {
        runner.withPropertyValues("nucleo.credentials.anthropic-api-key=sk-test").run(context -> {
            assertTrue(context.containsBean("nucleoSecrets"));
            assertTrue(context.containsBean("nucleoRuntime"));
            assertEquals("sk-test", context.getBean(SpringSecrets.class).find("anthropic-api-key").secret(),
                    "the context handed the store its environment");
            NucleoRuntime runtime = context.getBean(NucleoRuntime.class);
            assertFalse(runtime.isAutoStartup(), "discover mode keeps the runtime down");
            assertFalse(runtime.isRunning());
            assertNotNull(runtime.ledger());
        });
    }

    /** A picker that is nobody's shipped default, so its arrival proves the binder wrote the field. */
    public static class BoundPicker extends DefaultModelPicker {
    }

    @Test
    void nucleoPropertiesBindOntoTheSettingsClasses() {
        HttpSettings http = Settings.get(HttpSettings.class);
        int poolBefore = http.poolSize;
        Class<? extends ModelPicker> pickerBefore = Settings.get(ModelSettings.class).pickerClass;
        try {
            runner.withPropertyValues("nucleo.http.pool-size=1234",
                    "nucleo.model.picker-class=" + BoundPicker.class.getName()).run(context -> {
                assertEquals(1234, http.poolSize, "an int knob binds from its kebab-cased property");
                assertEquals(BoundPicker.class, Settings.get(ModelSettings.class).pickerClass,
                        "a class knob binds from the fully qualified name");
            });
        }
        finally {
            http.poolSize = poolBefore;
            Settings.get(ModelSettings.class).pickerClass = pickerBefore;
        }
    }

    @Test
    void printAllListsSettingsContributedByOtherJars() {
        assertTrue(NucleoConfigurator.printAll().contains("HttpSettings.poolSize = "),
                "nucleo-core's registered settings are visible from this artifact's classpath");
    }

    @Configuration
    static class HostsOwn {
        @Bean
        NucleoRuntime mine(ApplicationArguments arguments) {
            return new NucleoRuntime(arguments);
        }
    }

    @Test
    void aHostsOwnRuntimeBeanReplacesTheStarters() {
        runner.withUserConfiguration(HostsOwn.class).run(context -> {
            assertTrue(context.containsBean("mine"));
            assertFalse(context.containsBean("nucleoRuntime"), "the auto-configuration stepped aside");
            assertTrue(context.containsBean("nucleoSecrets"), "the store it did not declare still comes from the starter");
        });
    }

    @Test
    void aContextThatDidNotStartTheDispatcherLeavesItRunningWhenItCloses() {
        JobDispatcher dispatcher = JobDispatcher.getInstance();
        dispatcher.start();
        runner.run(context -> assertFalse(context.getBean(NucleoRuntime.class).isRunning(),
                "the runtime reports what it started, not the process-wide dispatcher"));
        assertTrue(dispatcher.isRunning(), "closing the context stopped nothing it had not started");
    }
}
