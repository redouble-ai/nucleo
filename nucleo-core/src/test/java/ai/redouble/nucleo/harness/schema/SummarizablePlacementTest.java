/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.processor.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import javax.tools.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.stream.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The check made when the dispatcher starts: a field that carries
 * {@code @LLMSummarizable} outside an artifact is found in the class files of the
 * application's classpath roots, a directory and a jar alike, and the start is refused
 * with every such field named; a field of an artifact is accepted. Also pins the names the
 * compile-time check knows the annotation and the artifact interface by to the real ones.
 *
 * <p>A class that breaks the rule cannot stand among test sources, since the annotation
 * processor refuses it there, so the fixtures are compiled here from source text with
 * annotation processing off.
 *
 * @author Andrey Santrosyan
 * @since 0.1.6 (2026-11-24)
 */
class SummarizablePlacementTest {
    private static final String PLAIN = """
            package placementfixture;
            import ai.redouble.nucleo.harness.schema.LLMSummarizable;
            public class PlainNote {
                @LLMSummarizable
                private String text;
                @LLMSummarizable
                private java.util.List<String> lines;
            }
            """;
    private static final String DOC = """
            package placementfixture;
            import ai.redouble.nucleo.harness.artifacts.AbstractArtifact;
            import ai.redouble.nucleo.harness.schema.LLMSummarizable;
            import ai.redouble.nucleo.harness.schema.TypeAlias;
            @TypeAlias("test:placement")
            public class GoodDoc extends AbstractArtifact {
                @LLMSummarizable
                private String body;
            }
            """;
    @TempDir
    Path work;

    /** Compiles the sources into a fresh directory under the work folder and returns it. */
    private Path compile(String folder, Map<String, String> sources) throws IOException {
        Path classes = Files.createDirectories(work.resolve(folder));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        List<JavaFileObject> units = new ArrayList<>();
        sources.forEach((name, text) -> units.add(new SimpleJavaFileObject(URI.create("string:///" + name.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return text;
            }
        }));
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<String> options = List.of("-proc:none", "-d", classes.toString(), "-classpath", System.getProperty("java.class.path"));
        assertTrue(compiler.getTask(null, null, diagnostics, options, null, units).call(), "the fixtures compile: " + diagnostics.getDiagnostics());
        return classes;
    }

    private static URLClassLoader loaderOf(URL root) {
        return new URLClassLoader(new URL[] {root}, SummarizablePlacementTest.class.getClassLoader());
    }

    @Test
    void aFieldOutsideAnArtifactIsNamedAndAFieldOfAnArtifactIsAccepted() throws Exception {
        URL root = compile("mixed", Map.of("placementfixture.PlainNote", PLAIN, "placementfixture.GoodDoc", DOC)).toUri().toURL();
        try (URLClassLoader loader = loaderOf(root)) {
            assertEquals(List.of("placementfixture.PlainNote.lines", "placementfixture.PlainNote.text"),
                    SummarizablePlacement.violations(List.of(root), loader),
                    "each field of the plain class, whatever its type, and no field of the artifact");
        }
        URL clean = compile("clean", Map.of("placementfixture.GoodDoc", DOC)).toUri().toURL();
        try (URLClassLoader loader = loaderOf(clean)) {
            assertEquals(List.of(), SummarizablePlacement.violations(List.of(clean), loader));
        }
    }

    @Test
    void theFieldsAreFoundInAJarReachedThroughAUrlClassLoader() throws Exception {
        Path classes = compile("jarred", Map.of("placementfixture.PlainNote", PLAIN));
        Path jar = work.resolve("fixture.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar)); Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace(File.separatorChar, '/')));
                Files.copy(file, out);
                out.closeEntry();
            }
        }
        // the form a launcher's class loader names a jar in: jar:<location>!/
        URL root = URI.create("jar:" + jar.toUri() + "!/").toURL();
        try (URLClassLoader loader = loaderOf(root)) {
            assertEquals(List.of("placementfixture.PlainNote.lines", "placementfixture.PlainNote.text"),
                    SummarizablePlacement.violations(List.of(root), loader));
        }
    }

    @Test
    void theCheckReadsTheRootsOfTheThreadsClassLoaderAndRefusesWithEveryFieldNamed() throws Exception {
        URL root = compile("context", Map.of("placementfixture.PlainNote", PLAIN)).toUri().toURL();
        ClassLoader before = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = loaderOf(root)) {
            Thread.currentThread().setContextClassLoader(loader);
            SummarizableOutsideArtifactException refused = assertThrows(SummarizableOutsideArtifactException.class, SummarizablePlacement::check);
            assertEquals(List.of("placementfixture.PlainNote.lines", "placementfixture.PlainNote.text"), refused.getFields());
            assertTrue(refused.getMessage().contains("placementfixture.PlainNote.text") && refused.getMessage().contains("implements Artifact"),
                    "the message names the field and says what is allowed: " + refused.getMessage());
            assertThrows(SummarizableOutsideArtifactException.class, SummarizablePlacement::check, "a refused class loader is checked again, never remembered as clean");
        }
        finally {
            Thread.currentThread().setContextClassLoader(before);
        }
    }

    @Test
    void thisModulesOwnClasspathPasses() {
        assertDoesNotThrow(SummarizablePlacement::check, "every annotated field of nucleo-core and of its tests is declared in an artifact");
    }

    @Test
    void theCompileTimeCheckKnowsTheAnnotationAndTheInterfaceByTheirRealNames() {
        assertEquals(LLMSummarizable.class.getName(), SummarizableFieldProcessor.SUMMARIZABLE,
                "the annotation processor depends on nothing of the runtime and finds the annotation by this name");
        assertEquals(Artifact.class.getName(), SummarizableFieldProcessor.ARTIFACT);
    }
}
