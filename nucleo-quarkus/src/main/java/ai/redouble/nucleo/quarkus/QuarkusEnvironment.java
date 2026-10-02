/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.quarkus;

import ai.redouble.nucleo.*;
import ai.redouble.nucleo.secrets.*;

/**
 * The one thing a Quarkus host tells the runtime in code: credentials are MicroProfile Config
 * properties, read by {@link QuarkusSecrets}. Everything else runs on the shipped defaults,
 * overridable per knob through {@code nucleo.*} application properties (bound by
 * {@link QuarkusSettingsBinder} at startup). Registered under
 * {@code META-INF/services/ai.redouble.nucleo.NucleoConfigurator}, which is how the runtime
 * finds it; a host with configuration of its own implements {@link NucleoConfigurator} and
 * names its class with {@code -Dnucleo.env}, which wins over the registration.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-22)
 */
public class QuarkusEnvironment implements NucleoConfigurator {
    @Override
    public void configure() {
        Settings.get(SecretsSettings.class).secretsClass = QuarkusSecrets.class;
    }
}
