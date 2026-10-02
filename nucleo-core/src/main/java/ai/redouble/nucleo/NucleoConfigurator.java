/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo;

/**
 * The whole configuration surface a deployment author touches: one class per deployment,
 * one method, assigning the {@link Settings} fields that differ from the shipped defaults.
 *
 * <pre>{@code
 * public class AcmeConfigurator implements NucleoConfigurator {
 *     @Override public void configure() {
 *         Settings.get(SecretsSettings.class).secretsClass = AcmeSecrets.class;
 *         Settings.get(HttpSettings.class).poolSize = 2000;
 *     }
 * }
 * }</pre>
 *
 * <p>Discovered once, at the first {@link Settings#get} anywhere: the class named by
 * {@code -D}{@value #CONFIG_PROPERTY} wins over any registration; else the single
 * registration under {@code META-INF/services/ai.redouble.nucleo.NucleoConfigurator} runs;
 * else the shipped defaults stand. Two registrations refuse at startup, naming both.
 * A library never ships a configurator - libraries ship {@link Settings} classes with
 * defaults; only the application ships the one configurator.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-18)
 */
public interface NucleoConfigurator {

    /** System property naming the deployment's configurator class; set, it wins over any services registration. */
    String CONFIG_PROPERTY = "nucleo.env";

    /** Assigns the settings fields that differ from the shipped defaults. Runs once, before the first settings read. */
    void configure();

    /** Every registered settings class with every field's current value - the whole-deployment view. */
    static String printAll() {
        return Settings.printAll();
    }
}
