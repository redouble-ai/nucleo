/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo;

import ai.redouble.nucleo.http.*;

/**
 * Probe main for {@code SettingsPoisonedConfiguratorTest}, run in a forked JVM because a
 * configurator failure poisons the process for good and the shared suite JVM already
 * configured successfully. Names a throwing configurator via {@code -Dnucleo.env} (set
 * before the first settings read), then verifies the poison contract: the first
 * {@code Settings.get} fails carrying the configurator's own exception as the cause, and
 * every later {@code Settings.get} rethrows that SAME first failure. The exit code is the
 * verdict: 0 all held, 1 a get did not refuse, 2 the first refusal's cause is not the
 * configurator's exception, 3 the second refusal carries a different cause.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-18)
 */
public final class PoisonedConfiguratorProbe {

    public static class ThrowingConfigurator implements NucleoConfigurator {
        @Override
        public void configure() {
            throw new IllegalStateException("deliberately broken");
        }
    }

    private PoisonedConfiguratorProbe() {
    }

    public static void main(String[] args) {
        System.setProperty(NucleoConfigurator.CONFIG_PROPERTY, ThrowingConfigurator.class.getName());
        IllegalStateException first = refusal();
        IllegalStateException second = refusal();
        if (first == null || second == null) {
            System.exit(1);
        }
        if (!(first.getCause() instanceof IllegalStateException broken) || !"deliberately broken".equals(broken.getMessage())) {
            System.exit(2);
        }
        if (second.getCause() != first.getCause()) {
            System.exit(3);
        }
        System.exit(0);
    }

    private static IllegalStateException refusal() {
        try {
            Settings.get(HttpSettings.class);
            return null;
        }
        catch (IllegalStateException e) {
            return e;
        }
    }
}
