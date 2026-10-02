/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import ai.redouble.nucleo.harness.errors.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;
import java.net.*;
import java.nio.charset.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Where a deployment's catalog is read from, the way logback finds its configuration: the file
 * {@code -Dnucleo.models} names wins, then every {@code models.json} on the classpath with the
 * nearest to the application taken and the shadowed ones warned about, and nothing else - a file
 * in the process's working directory is no source. A named file that cannot be read is refused.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-17)
 */
class CatalogFileTest {
    private static String pinsCatalog() throws Exception {
        return new String(CatalogFileTest.class.getResourceAsStream("/models-pins.json").readAllBytes(), StandardCharsets.UTF_8);
    }

    /** A classpath of temp directories, some holding a models.json, ordered like a launcher orders entries. */
    private static ClassLoader classpath(Path... entries) throws Exception {
        URL[] urls = new URL[entries.length];
        for (int i = 0; i < entries.length; i++) {
            urls[i] = entries[i].toUri().toURL();
        }
        return new URLClassLoader(urls, null);
    }

    @Test
    void thePropertyWinsThenTheClasspath(@TempDir Path dir) throws Exception {
        Path named = Files.writeString(dir.resolve("named.json"), pinsCatalog());
        // a file in the process's directory is no source: only the property and the classpath are
        Files.writeString(dir.resolve("models.json"), pinsCatalog());
        Path entry = Files.createDirectory(dir.resolve("classes"));
        Files.writeString(entry.resolve("models.json"), pinsCatalog());
        ClassLoader loader = classpath(entry);
        assertEquals(named.toString(), JsonModelsBackend.deploymentLayer(named.toString(), loader).name());
        assertEquals(entry.resolve("models.json").toUri().toURL().toString(), JsonModelsBackend.deploymentLayer(null, loader).name(),
                "no property: the classpath, like logging configuration");
        assertNull(JsonModelsBackend.deploymentLayer(null, classpath(Files.createDirectory(dir.resolve("empty")))),
                "nothing on the classpath: null, and the caller falls back to the shipped fragments");
    }

    @Test
    void theNearestClasspathCopyWinsOverTheOnesItShadows(@TempDir Path dir) throws Exception {
        Path application = Files.createDirectory(dir.resolve("app-classes"));
        Files.writeString(application.resolve("models.json"), pinsCatalog());
        Path dependency = Files.createDirectory(dir.resolve("dependency"));
        String shadowed = pinsCatalog().replace("\"SMALL\": [\"pinned-small\"]", "\"SMALL\": [\"other-small\"]");
        assertTrue(shadowed.contains("other-small"), "the dependency's copy differs from the application's");
        Files.writeString(dependency.resolve("models.json"), shadowed);
        JsonModelsBackend.Layer nearest = JsonModelsBackend.deploymentLayer(null, classpath(application, dependency));
        assertEquals(application.resolve("models.json").toUri().toURL().toString(), nearest.name(),
                "launchers put the application's own classes first, so its file shadows a dependency's");
        assertTrue(nearest.json().contains("\"SMALL\": [\"pinned-small\"]"));
    }

    @Test
    void aNamedFileThatIsNotThereIsRefusedNamingIt(@TempDir Path dir) throws Exception {
        String missing = dir.resolve("missing.json").toString();
        ClassLoader empty = classpath(Files.createDirectory(dir.resolve("empty")));
        UncorrectableRuntimeLLMException refusal = assertThrows(UncorrectableRuntimeLLMException.class,
                () -> JsonModelsBackend.deploymentLayer(missing, empty));
        assertTrue(refusal.getMessage().contains(missing), refusal.getMessage());
    }

    @Test
    void theNamedFileIsThePropertysAndNoneWithoutIt() {
        String before = System.getProperty(JsonModelsBackend.PROPERTY);
        try {
            System.clearProperty(JsonModelsBackend.PROPERTY);
            assertNull(JsonModelsBackend.namedFile(), "no property: nothing is named, and the classpath is read");
            System.setProperty(JsonModelsBackend.PROPERTY, "some/where.json");
            assertEquals(Path.of("some/where.json").toAbsolutePath(), JsonModelsBackend.namedFile());
        }
        finally {
            if (before == null) {
                System.clearProperty(JsonModelsBackend.PROPERTY);
            }
            else {
                System.setProperty(JsonModelsBackend.PROPERTY, before);
            }
        }
    }
}
