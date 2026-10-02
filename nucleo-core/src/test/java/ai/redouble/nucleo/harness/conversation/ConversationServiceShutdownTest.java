/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import org.junit.jupiter.api.*;

import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins {@link ConversationService}'s shutdown contract by forking a JVM: doStop shuts the
 * cleanup executor down within its 5-second grace, clears every conversation from memory,
 * refuses all new work with IllegalStateException, and a second stop() is a safe no-op.
 * Forked because stopping the singleton is final - running it in the shared test JVM
 * would poison every test after it. The checks themselves live in
 * {@link ConversationServiceShutdownProbe}; this side asserts the probe's exit code and
 * relays its output on failure.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-18)
 */
class ConversationServiceShutdownTest {

    @Test
    void shutdownContractHoldsInAForkedJvm() throws Exception {
        String java = System.getProperty("java.home") + "/bin/java";
        ProcessBuilder builder = new ProcessBuilder(java, ConversationServiceShutdownProbe.class.getName());
        builder.environment().put("CLASSPATH", System.getProperty("java.class.path"));
        builder.redirectErrorStream(true);
        Process probe = builder.start();
        String output = new String(probe.getInputStream().readAllBytes());
        assertTrue(probe.waitFor(120, TimeUnit.SECONDS), "the probe JVM must exit; output so far:\n" + output);
        assertEquals(0, probe.exitValue(), "a probe check failed:\n" + output);
    }
}
