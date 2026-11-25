/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.quarkus.deployment;

import org.jboss.jandex.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import javax.tools.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The build's check of where {@code @LLMSummarizable} stands: a field declared in a class
 * that is no artifact is named, a field of an artifact is accepted, whether the artifact's
 * ancestry is in the index or is reached through the build's class loader.
 *
 * <p>A class that breaks the rule cannot stand among test sources, since the annotation
 * processor refuses it there, so the fixtures are compiled here from source text with
 * annotation processing off, and indexed from their class files as a build indexes an
 * application.
 *
 * @author Andrey Santrosyan
 * @since 0.1.6 (2026-11-24)
 */
class SummarizablePlacementBuildStepTest {
    private static final Map<String, String> SOURCES = Map.of(
            "buildfixture.PlainNote", """
                    package buildfixture;
                    import ai.redouble.nucleo.harness.schema.LLMSummarizable;
                    public class PlainNote {
                        @LLMSummarizable
                        private String text;
                        @LLMSummarizable
                        private java.util.Map<String, String> table;
                    }
                    """,
            "buildfixture.Marker", """
                    package buildfixture;
                    import ai.redouble.nucleo.harness.artifacts.Artifact;
                    public interface Marker extends Artifact {
                    }
                    """,
            "buildfixture.ByInterface", """
                    package buildfixture;
                    import ai.redouble.nucleo.harness.artifacts.ArtifactSummaries;
                    import ai.redouble.nucleo.harness.schema.LLMSummarizable;
                    public class ByInterface implements Marker {
                        @LLMSummarizable
                        private String body;
                        public String getArtifactRef() { return null; }
                        public void setArtifactRef(String ref) { }
                        public ArtifactSummaries getSummaries() { return null; }
                    }
                    """,
            "buildfixture.ByParent", """
                    package buildfixture;
                    import ai.redouble.nucleo.harness.artifacts.AbstractArtifact;
                    import ai.redouble.nucleo.harness.schema.LLMSummarizable;
                    import ai.redouble.nucleo.harness.schema.TypeAlias;
                    @TypeAlias("test:buildstep")
                    public class ByParent extends AbstractArtifact {
                        @LLMSummarizable
                        private String body;
                    }
                    """);
    @TempDir
    Path work;

    private Path compile(Collection<String> names) throws IOException {
        Path classes = Files.createDirectories(work.resolve("classes-" + names.size()));
        List<JavaFileObject> units = new ArrayList<>();
        for (String name : names) {
            units.add(new SimpleJavaFileObject(URI.create("string:///" + name.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return SOURCES.get(name);
                }
            });
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<String> options = List.of("-proc:none", "-d", classes.toString(), "-classpath", System.getProperty("java.class.path"));
        assertTrue(ToolProvider.getSystemJavaCompiler().getTask(null, null, diagnostics, options, null, units).call(), "the fixtures compile: " + diagnostics.getDiagnostics());
        return classes;
    }

    private static Index indexOf(Path classes) throws IOException {
        Indexer indexer = new Indexer();
        try (Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                try (InputStream in = Files.newInputStream(file)) {
                    indexer.index(in);
                }
            }
        }
        return indexer.complete();
    }

    @Test
    void aFieldOutsideAnArtifactIsNamedAndFieldsOfArtifactsAreAccepted() throws Exception {
        Path classes = compile(SOURCES.keySet());
        try (URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
            assertEquals(List.of("buildfixture.PlainNote.table", "buildfixture.PlainNote.text"),
                    NucleoQuarkusProcessor.summarizableOutsideArtifact(indexOf(classes), loader),
                    "each field of the plain class, whatever its type; none of the class that is an artifact through an indexed interface,"
                            + " nor of the one whose parent is in no index and is reached through the class loader");
        }
    }

    @Test
    void anIndexOfArtifactsAloneHasNothingToName() throws Exception {
        Path classes = compile(List.of("buildfixture.ByParent"));
        try (URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
            assertEquals(List.of(), NucleoQuarkusProcessor.summarizableOutsideArtifact(indexOf(classes), loader));
        }
    }
}
