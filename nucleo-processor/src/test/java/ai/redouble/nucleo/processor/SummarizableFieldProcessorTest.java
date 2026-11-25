/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.processor;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import javax.tools.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SummarizableFieldProcessor} refuses an {@code @LLMSummarizable} field declared in a
 * class that is no artifact, and accepts one declared in an artifact.
 *
 * <p>A class that breaks the rule cannot stand among test sources, since the processor
 * refuses it there too, so each case is compiled here from source text. The annotation and
 * the artifact interface are stand-ins under the runtime's own names: the processor knows
 * both by name alone.
 *
 * @author Andrey Santrosyan
 * @since 0.1.6 (2026-11-24)
 */
class SummarizableFieldProcessorTest {
    private static final String ANNOTATION = """
            package ai.redouble.nucleo.harness.schema;
            import java.lang.annotation.*;
            @Target(ElementType.FIELD) @Retention(RetentionPolicy.RUNTIME)
            public @interface LLMSummarizable {}
            """;
    private static final String ARTIFACT = """
            package ai.redouble.nucleo.harness.artifacts;
            public interface Artifact {}
            """;
    @TempDir
    Path output;

    @Test
    void aFieldOfAPlainClassIsAnErrorOnItsLine() {
        List<Diagnostic<? extends JavaFileObject>> errors = compile(true, source("fixture.Plain", """
                package fixture;
                import ai.redouble.nucleo.harness.schema.LLMSummarizable;
                public class Plain {
                    @LLMSummarizable
                    private String body;
                }
                """));
        assertEquals(1, errors.size(), "one misplaced field is one error: " + errors);
        assertEquals(5, errors.getFirst().getLineNumber(), "the error is reported on the line of the field");
        String message = errors.getFirst().getMessage(Locale.ROOT);
        assertTrue(message.contains("Plain") && message.contains("'body'"), "the error names the class and the field: " + message);
    }

    @Test
    void aFieldOfAnArtifactAndOfASubclassOfOneIsAccepted() {
        List<Diagnostic<? extends JavaFileObject>> errors = compile(true,
                source("fixture.Paper", """
                        package fixture;
                        import ai.redouble.nucleo.harness.artifacts.Artifact;
                        import ai.redouble.nucleo.harness.schema.LLMSummarizable;
                        public class Paper implements Artifact {
                            @LLMSummarizable
                            private java.util.List<String> lines;
                        }
                        """),
                source("fixture.Preprint", """
                        package fixture;
                        import ai.redouble.nucleo.harness.schema.LLMSummarizable;
                        public class Preprint extends Paper {
                            @LLMSummarizable
                            private String body;
                        }
                        """));
        assertTrue(errors.isEmpty(), "a field declared in an artifact, of any type, is legal: " + errors);
    }

    @Test
    void aFieldDeclaredInANonArtifactBaseIsRefusedThoughAnArtifactInheritsIt() {
        List<Diagnostic<? extends JavaFileObject>> errors = compile(true,
                source("fixture.Base", """
                        package fixture;
                        import ai.redouble.nucleo.harness.schema.LLMSummarizable;
                        public class Base {
                            @LLMSummarizable
                            protected String body;
                        }
                        """),
                source("fixture.Derived", """
                        package fixture;
                        import ai.redouble.nucleo.harness.artifacts.Artifact;
                        public class Derived extends Base implements Artifact {}
                        """));
        assertEquals(1, errors.size(), "the declaring class is what is judged: " + errors);
        assertTrue(errors.getFirst().getMessage(Locale.ROOT).contains("Base"), "the error names the class that declares the field");
    }

    @Test
    void aGenericArtifactAndAStaticFieldAreJudgedByTheDeclaringClassToo() {
        List<Diagnostic<? extends JavaFileObject>> errors = compile(true,
                source("fixture.Page", """
                        package fixture;
                        import ai.redouble.nucleo.harness.artifacts.Artifact;
                        import ai.redouble.nucleo.harness.schema.LLMSummarizable;
                        public class Page<T> implements Artifact {
                            @LLMSummarizable
                            private T body;
                        }
                        """),
                source("fixture.Constants", """
                        package fixture;
                        import ai.redouble.nucleo.harness.schema.LLMSummarizable;
                        public enum Constants {
                            ONE;
                            @LLMSummarizable
                            static String text;
                        }
                        """));
        assertEquals(1, errors.size(), "a generic artifact is an artifact, and an enum that is none is refused: " + errors);
        assertTrue(errors.getFirst().getMessage(Locale.ROOT).contains("Constants"), "the error names the enum");
    }

    @Test
    void anAnnotatedFieldWithNoArtifactInterfaceOnTheClasspathIsAnError() {
        List<Diagnostic<? extends JavaFileObject>> errors = compile(false, source("fixture.Plain", """
                package fixture;
                import ai.redouble.nucleo.harness.schema.LLMSummarizable;
                public class Plain {
                    @LLMSummarizable
                    private String body;
                }
                """));
        assertEquals(1, errors.size(), "a field that cannot be judged is refused: " + errors);
        assertTrue(errors.getFirst().getMessage(Locale.ROOT).contains(SummarizableFieldProcessor.ARTIFACT), "the error names the missing interface");
    }

    /** Compiles the sources with the processor and yields the errors it and the compiler report. */
    private List<Diagnostic<? extends JavaFileObject>> compile(boolean withArtifact, JavaFileObject... sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<JavaFileObject> units = new ArrayList<>(List.of(sources));
        units.add(source("ai.redouble.nucleo.harness.schema.LLMSummarizable", ANNOTATION));
        if (withArtifact) {
            units.add(source("ai.redouble.nucleo.harness.artifacts.Artifact", ARTIFACT));
        }
        JavaCompiler.CompilationTask task = compiler.getTask(null, null, diagnostics, List.of("-d", output.toString()), null, units);
        task.setProcessors(List.of(new SummarizableFieldProcessor()));
        task.call();
        return diagnostics.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).toList();
    }

    private static JavaFileObject source(String className, String text) {
        return new SimpleJavaFileObject(URI.create("string:///" + className.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return text;
            }
        };
    }
}
