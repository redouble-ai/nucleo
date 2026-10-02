/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo;

import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.http.*;
import ai.redouble.nucleo.secrets.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The settings pattern's contract: one instance per settings type through {@link Settings#get};
 * the deployment's configurator is the class named by {@code -Dnucleo.env}, else the single
 * services registration, else none (shipped defaults); two registrations refuse naming both;
 * and {@link NucleoConfigurator#printAll} shows every registered knob with its current value,
 * including what the configurator assigned.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-18)
 */
class SettingsContractTest {

    public static class NamedConfigurator implements NucleoConfigurator {
        @Override public void configure() {
        }
    }

    public static class RegisteredConfigurator implements NucleoConfigurator {
        @Override public void configure() {
        }
    }

    public static class RivalConfigurator implements NucleoConfigurator {
        @Override public void configure() {
        }
    }

    @Test
    void oneInstancePerSettingsType() {
        assertSame(Settings.get(HttpSettings.class), Settings.get(HttpSettings.class),
                "every reader of a settings type sees the same instance");
    }

    @Test
    void aNamedConfiguratorWinsOverAnyRegistration() {
        NucleoConfigurator chosen = Settings.choose(NamedConfigurator.class.getName(), List.of(new RegisteredConfigurator()));
        assertInstanceOf(NamedConfigurator.class, chosen, "-Dnucleo.env names the winner; registrations are ignored");
    }

    @Test
    void theSingleRegistrationRuns() {
        NucleoConfigurator registered = new RegisteredConfigurator();
        assertSame(registered, Settings.choose(null, List.of(registered)), "one registration is the deployment's configurator");
    }

    @Test
    void noConfiguratorMeansShippedDefaults() {
        assertNull(Settings.choose(null, List.of()), "a classpath registering none runs on the shipped defaults");
    }

    @Test
    void twoRegistrationsRefuseNamingBoth() {
        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> Settings.choose(null, List.of(new RegisteredConfigurator(), new RivalConfigurator())),
                "the classpath order that would pick one silently is never a configuration");
        assertTrue(refusal.getMessage().contains(RegisteredConfigurator.class.getName()), "the refusal names the first");
        assertTrue(refusal.getMessage().contains(RivalConfigurator.class.getName()), "the refusal names the second");
        assertTrue(refusal.getMessage().contains(NucleoConfigurator.CONFIG_PROPERTY), "the refusal says how to disambiguate");
    }

    @Test
    void printAllShowsEveryRegisteredKnobWithItsCurrentValue() {
        HttpSettings http = Settings.get(HttpSettings.class);
        int before = http.poolSize;
        try {
            http.poolSize = 1717;
            String all = NucleoConfigurator.printAll();
            assertTrue(all.contains("HttpSettings.poolSize = 1717"), "a mutated knob shows its live value");
            assertTrue(all.contains("McpSettings.stdioPoolMax = "), "a registered settings class the test never touched is listed");
            assertTrue(all.contains("SecretsSettings.secretsClass = "), "class-typed knobs print the class name");
            assertTrue(all.contains("ModelSettings.pickerClass = " + TestModelPicker.class.getName()),
                    "what the discovered configurator assigned is visible");
        }
        finally {
            http.poolSize = before;
        }
    }
}
