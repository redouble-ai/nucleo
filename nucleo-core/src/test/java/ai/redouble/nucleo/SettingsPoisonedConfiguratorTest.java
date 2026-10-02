/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo;

import org.junit.jupiter.api.*;

import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A configurator that throws poisons the runtime: the first {@link Settings#get} fails
 * carrying the configurator's exception as the cause, and every later get rethrows that
 * same first failure rather than running on half-applied configuration. Verified in a
 * forked JVM ({@link PoisonedConfiguratorProbe}) because the poison is permanent per
 * process and this suite's JVM configured successfully.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-18)
 */
class SettingsPoisonedConfiguratorTest {

    @Test
    void aThrowingConfiguratorPoisonsEveryLaterGet() throws Exception {
        ProcessBuilder probe = new ProcessBuilder(
                System.getProperty("java.home") + "/bin/java",
                PoisonedConfiguratorProbe.class.getName());
        probe.environment().put("CLASSPATH", System.getProperty("java.class.path"));
        probe.redirectErrorStream(true);
        Process process = probe.start();
        byte[] output = process.getInputStream().readAllBytes();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the probe JVM finishes");
        assertEquals(0, process.exitValue(),
                "the probe verdict (1 = a get did not refuse, 2 = wrong first cause, 3 = a later get carried a"
                        + " different cause). Probe output:\n" + new String(output));
    }
}
