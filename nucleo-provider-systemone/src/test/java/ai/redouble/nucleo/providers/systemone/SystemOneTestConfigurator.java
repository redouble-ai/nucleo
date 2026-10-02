/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.systemone;

import ai.redouble.nucleo.*;
import ai.redouble.nucleo.secrets.*;

/**
 * This module's configurator for its dispatcher-driven tests, named by {@code -Dnucleo.env}
 * in the surefire configuration: the credential store is the test's own, pointing at the
 * local server a test starts, and the picker stays the shipped default so the decision
 * declaration comes from the test catalog's pins, as a deployment's would.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public class SystemOneTestConfigurator implements NucleoConfigurator {
    @Override
    public void configure() {
        Settings.get(SecretsSettings.class).secretsClass = SystemOneTestSecrets.class;
    }
}
