/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

import ai.redouble.nucleo.harness.artifacts.*;
import org.reflections.*;
import org.reflections.scanners.*;
import org.reflections.util.*;
import org.slf4j.*;

import java.net.*;
import java.util.*;

/**
 * The check, made when the dispatcher starts, that every field carrying
 * {@link LLMSummarizable} is declared in a class that implements {@link Artifact}. It
 * reads the class files of every root the application's class loader names, so it sees a
 * class no code has touched yet. The same rule is checked when the code is compiled, by
 * the annotation processor of {@code nucleo-processor}, and when a Quarkus application is
 * built; this check is the one that holds for a build that used neither.
 *
 * @author Andrey Santrosyan
 * @since 0.1.6 (2026-11-24)
 */
public final class SummarizablePlacement {
    private static final Logger log = LoggerFactory.getLogger(SummarizablePlacement.class);
    /** The class loaders already checked: a second dispatcher under one loader reads nothing again. */
    private static final Map<ClassLoader, Boolean> CHECKED = Collections.synchronizedMap(new WeakHashMap<>());

    private SummarizablePlacement() {}

    /**
     * Checks the classpath of the thread's context class loader, once per class loader.
     *
     * @throws SummarizableOutsideArtifactException naming every annotated field declared
     *         outside an artifact
     */
    public static void check() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        ClassLoader loader = context != null ? context : SummarizablePlacement.class.getClassLoader();
        if (CHECKED.containsKey(loader)) {
            return;
        }
        // forClassLoader answers the roots of a URL class loader and its parents, which is
        // what an application server and a nested-jar launcher give; forJavaClassPath
        // answers the rest, where the application's loader is the JDK's own
        Set<URL> roots = new LinkedHashSet<>(ClasspathHelper.forClassLoader(loader));
        roots.addAll(ClasspathHelper.forJavaClassPath());
        List<String> misplaced = violations(roots, loader);
        if (!misplaced.isEmpty()) {
            throw new SummarizableOutsideArtifactException(misplaced);
        }
        CHECKED.put(loader, Boolean.TRUE);
    }

    /**
     * The annotated fields under the given roots whose declaring class is no artifact, each
     * as {@code declaring.Class.fieldName}, sorted. A class that cannot be loaded is logged
     * and left out: a class that never loads serializes nothing.
     *
     * @param roots the classpath roots to read class files from
     * @param loader the class loader the declaring classes are loaded through
     */
    static List<String> violations(Collection<URL> roots, ClassLoader loader) {
        if (roots.isEmpty()) {
            log.warn("No classpath root to check @LLMSummarizable fields in: the class loader {} names none", loader);
            return List.of();
        }
        Reflections reflections = new Reflections(new ConfigurationBuilder()
                .setUrls(roots)
                .addClassLoaders(loader)
                .setScanners(Scanners.FieldsAnnotated));
        // the store's own names, so that no class is loaded by the lookup itself
        Set<String> annotated = reflections.getStore()
                .getOrDefault(Scanners.FieldsAnnotated.index(), Map.of())
                .getOrDefault(LLMSummarizable.class.getName(), Set.of());
        List<String> misplaced = new ArrayList<>();
        for (String field : annotated) {
            String declaring = field.substring(0, field.lastIndexOf('.'));
            try {
                if (!Artifact.class.isAssignableFrom(Class.forName(declaring, false, loader))) {
                    misplaced.add(field);
                }
            }
            catch (ClassNotFoundException | LinkageError e) {
                log.warn("Cannot load {} to check its @LLMSummarizable field {}: {}", declaring, field, e.toString());
            }
        }
        Collections.sort(misplaced);
        log.info("Checked {} @LLMSummarizable field(s) under {} classpath root(s): {} declared outside an artifact",
                annotated.size(), roots.size(), misplaced.size());
        return misplaced;
    }
}
