/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The corpus manifest is the list a native image extracts by, since it cannot list the corpus
 * directory: it must name exactly the files shipped under {@code corpus/}, and every file it names
 * must be readable by its exact resource path (the read a native image can do). A corpus file added
 * without a manifest line, or a manifest line naming a file that is not shipped, fails here rather
 * than going missing when the demo runs natively.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
class DemoCorpusTest {
    private static ClassLoader loader() {
        return DemoCorpusTest.class.getClassLoader();
    }

    @Test
    void theManifestNamesExactlyTheFilesShippedUnderCorpus() throws Exception {
        Set<String> manifest = new TreeSet<>();
        try (InputStream in = loader().getResourceAsStream(DemoCorpus.MANIFEST)) {
            assertNotNull(in, DemoCorpus.MANIFEST + " is on the classpath");
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.strip().isEmpty()) {
                    manifest.add(line.strip());
                }
            }
        }
        URL root = loader().getResource(DemoCorpus.CLASSPATH_ROOT);
        assertNotNull(root, "the corpus is on the classpath");
        Path directory = Path.of(root.toURI());
        Set<String> shipped = new TreeSet<>();
        try (var walk = Files.walk(directory)) {
            walk.filter(Files::isRegularFile)
                    .forEach(file -> shipped.add(directory.relativize(file).toString().replace(File.separatorChar, '/')));
        }
        assertEquals(shipped, manifest, "corpus.manifest must list exactly the files under corpus/");
    }

    @Test
    void everyManifestedFileIsReadableByItsExactResourcePath() throws Exception {
        try (InputStream in = loader().getResourceAsStream(DemoCorpus.MANIFEST)) {
            assertNotNull(in);
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String name = line.strip();
                if (name.isEmpty()) {
                    continue;
                }
                String resource = DemoCorpus.CLASSPATH_ROOT + "/" + name;
                try (InputStream file = loader().getResourceAsStream(resource)) {
                    assertNotNull(file, resource + " must be readable by its exact resource path");
                }
            }
        }
    }
}
