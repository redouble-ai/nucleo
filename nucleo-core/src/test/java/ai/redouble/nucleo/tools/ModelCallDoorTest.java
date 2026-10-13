/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools;

import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every exchange with a model in the runtime goes through one door: {@link ResponseCorrection#exchange}
 * is the one place that sends a conversation on a client, and every model-dependent tool, a
 * thinker's turn included, converses through it. The rule cannot be enforced on code outside
 * the runtime, so this pins it on the runtime's own sources: the only files that call
 * {@code singleResponse} or {@code streamResponse} on a client are the door itself and the
 * observability wrapper that delegates to the real client.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-13)
 */
class ModelCallDoorTest {
    private static final Set<String> SANCTIONED = Set.of(
            "ai/redouble/nucleo/tools/ResponseCorrection.java",
            "ai/redouble/nucleo/harness/ObservableLLMClient.java");

    @Test
    void onlyTheDoorAndTheObservabilityWrapperSendAConversationOnAClient() throws IOException {
        Path sources = Path.of("src/main/java");
        assertTrue(Files.isDirectory(sources), "the test runs from the module directory, where src/main/java is");
        Set<String> callers = new TreeSet<>();
        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(file)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                        continue;
                    }
                    if (trimmed.contains(".singleResponse(") || trimmed.contains(".streamResponse(")) {
                        callers.add(sources.relativize(file).toString().replace(File.separatorChar, '/'));
                    }
                }
            }
        }
        assertEquals(SANCTIONED, callers,
                "a conversation is sent on a client by ResponseCorrection.exchange alone; a new caller is a model call"
                        + " outside the tool base, with no correction, no seat and no reservation of its own");
    }
}
