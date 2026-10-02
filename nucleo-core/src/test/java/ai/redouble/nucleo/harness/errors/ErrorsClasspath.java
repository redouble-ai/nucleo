/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * Every class of the runtime, loaded without initialization, so a test can hold a documented
 * membership list against what is actually on the classpath: the whole LLM-readable hierarchy,
 * the whole set of HTTP carriers. Walks the class directory the runtime was loaded from, so it
 * sees nested classes and package-private ones alike.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
public final class ErrorsClasspath {

    private ErrorsClasspath() {
    }

    public static List<Class<?>> runtimeClasses() throws IOException, URISyntaxException {
        Path root = Paths.get(LLMReadable.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        ClassLoader loader = LLMReadable.class.getClassLoader();
        try (Stream<Path> files = Files.walk(root)) {
            List<Class<?>> classes = new ArrayList<>();
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                String name = root.relativize(file).toString().replace(File.separatorChar, '.').replaceAll("\\.class$", "");
                if (name.endsWith("package-info") || name.endsWith("module-info")) {
                    continue;
                }
                try {
                    classes.add(Class.forName(name, false, loader));
                }
                catch (ClassNotFoundException | NoClassDefFoundError e) {
                    throw new IllegalStateException("The runtime's own class directory holds a class that cannot load: " + name, e);
                }
            }
            return classes;
        }
    }
}
