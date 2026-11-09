/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The "no hardcoded models" rule as a test: catalog spec ids appear in {@code models.json}
 * and nowhere else in Nucleo's production sources, the shipped pickers included - a picker
 * is policy over the catalog's entries, and which entry serves a grade is the catalog's
 * {@code pins}. Call sites declare grades, or a {@code ModelRole} that names its entry in
 * application code; Nucleo itself declares no role.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-28)
 */
class CatalogIdLeakTest {

    @Test
    void productionSourcesNameNoCatalogIds() throws IOException {
        Path mainJava = Path.of("src/main/java");
        List<String> ids = Models.all().stream().map(ModelSpec::getId).toList();
        List<String> leaks = new ArrayList<>();
        try (Stream<Path> files = Files.walk(mainJava)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                // Provider clients speak WIRE vocabulary (for some entries the catalog id equals
                // the wire id) and are handed specs - a client cannot choose a model, so its
                // literals are wire mapping, not policy.
                if (file.toString().contains("/providers/") && name.endsWith("Client.java")) {
                    continue;
                }
                String source = Files.readString(file);
                for (String id : ids) {
                    if (source.contains("\"" + id + "\"")) {
                        leaks.add(file + " names " + id);
                    }
                }
            }
        }
        assertTrue(leaks.isEmpty(), "catalog ids outside models.json:\n" + String.join("\n", leaks));
    }
}
