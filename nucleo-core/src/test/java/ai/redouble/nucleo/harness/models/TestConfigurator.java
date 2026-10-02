/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import ai.redouble.nucleo.*;

/**
 * The runtime's configurator for its own test runs, registered under META-INF/services in
 * the test resources so {@link Settings} discovers it on the test classpath (and on the
 * classpath of every module that pulls the runtime's test-jar). The test picker pins each
 * grade to the specs the suite has always exercised.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-11)
 */
public class TestConfigurator implements NucleoConfigurator {
    @Override
    public void configure() {
        Settings.get(ModelSettings.class).pickerClass = TestModelPicker.class;
    }
}
