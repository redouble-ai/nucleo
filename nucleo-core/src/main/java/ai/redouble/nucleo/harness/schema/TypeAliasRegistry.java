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

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/**
 * Singleton registry mapping {@link TypeAlias} values to classes and vice versa.
 *
 * <p>Provides bidirectional lookup for serialization (class to alias) and
 * deserialization (alias to class). Aliases use hierarchical colon-separated
 * naming where colons denote IS-A relationships (e.g. {@code "link:cite:pubmed"}
 * means PubMedArticle IS-A CitationArtifact IS-A LinkArtifact).
 *
 * <p>Initialization scans the named packages for all {@code @TypeAlias}-annotated classes,
 * validates uniqueness, and ensures every concrete {@link Artifact} implementor in them carries
 * the annotation. It runs once per process: a later call is a no-op.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-19)
 */
public class TypeAliasRegistry {
    private static final Logger log = LoggerFactory.getLogger(TypeAliasRegistry.class);
    private static final Map<String, Class<?>> aliasToClass = new ConcurrentHashMap<>();
    private static final Map<Class<?>, String> classToAlias = new ConcurrentHashMap<>();
    private static volatile boolean initialized = false;

    /**
     * Scans the given packages, and their subpackages, for all {@code @TypeAlias}-annotated
     * classes, registers them, validates uniqueness, and checks that every concrete
     * {@link Artifact} implementor found has the annotation. Once it has succeeded, a later
     * call is a no-op.
     *
     * @param basePackages packages to scan (e.g. "ai.redouble"): a class is scanned when its
     *     binary name starts with one of them followed by a dot, whatever classpath root holds it
     * @throws IllegalStateException if one alias is declared on two classes
     * @throws MissingTypeAliasException if a concrete {@link Artifact} class carries no annotation
     */
    public static synchronized void init(String... basePackages) {
        if (initialized) {
            return;
        }
        log.info("TypeAliasRegistry: scanning packages {}", Arrays.toString(basePackages));
        // ClasspathHelper.forPackage answers the ROOTS that hold a package, so the URLs alone
        // would scan every class under those roots; the input filter keeps the scan to the
        // packages named, matched on the dotted binary name Reflections hands it.
        ConfigurationBuilder configuration = new ConfigurationBuilder().setScanners(Scanners.SubTypes, Scanners.TypesAnnotated);
        FilterBuilder packages = new FilterBuilder();
        for (String basePackage : basePackages) {
            configuration.addUrls(ClasspathHelper.forPackage(basePackage));
            packages.includePattern(Pattern.quote(basePackage + ".") + ".*");
        }
        Reflections reflections = new Reflections(configuration.filterInputsBy(packages));

        // Register all @TypeAlias-annotated classes
        Set<Class<?>> annotated = reflections.getTypesAnnotatedWith(TypeAlias.class);
        for (Class<?> clazz : annotated) {
            register(clazz);
        }

        // Validate: every concrete Artifact implementor must have @TypeAlias
        Set<Class<? extends Artifact>> artifactClasses = reflections.getSubTypesOf(Artifact.class);
        Set<Class<?>> missing = new LinkedHashSet<>();
        for (Class<? extends Artifact> clazz : artifactClasses) {
            if (clazz.isInterface() || Modifier.isAbstract(clazz.getModifiers())) {
                continue;
            }
            if (!clazz.isAnnotationPresent(TypeAlias.class)) {
                missing.add(clazz);
            }
        }
        if (!missing.isEmpty()) {
            throw new MissingTypeAliasException(missing);
        }
        initialized = true;
        log.info("TypeAliasRegistry: registered {} aliases ({} classes)", aliasToClass.size(), classToAlias.size());
    }

    /**
     * Registers a single class. Reads {@code @TypeAlias} if present.
     * Classes without the annotation are silently ignored.
     *
     * @param clazz the class to register
     * @throws IllegalStateException if the alias is already registered to a different class
     */
    public static void register(Class<?> clazz) {
        TypeAlias annotation = clazz.getAnnotation(TypeAlias.class);
        if (annotation == null) {
            return;
        }
        String alias = annotation.value();
        Class<?> existing = aliasToClass.get(alias);
        if (existing != null && existing != clazz) {
            throw new IllegalStateException(
                "Duplicate @TypeAlias(\"" + alias + "\"): " +
                existing.getName() + " and " + clazz.getName());
        }
        aliasToClass.put(alias, clazz);
        classToAlias.put(clazz, alias);
    }

    /**
     * Returns the alias for a class, or null if the class has no {@code @TypeAlias}.
     *
     * @param clazz the class to look up
     * @return the alias, or null
     */
    public static String getAlias(Class<?> clazz) {
        String alias = classToAlias.get(clazz);
        if (alias != null) {
            return alias;
        }

        // Lazy registration for classes not yet seen
        TypeAlias annotation = clazz.getAnnotation(TypeAlias.class);
        if (annotation != null) {
            register(clazz);
            return annotation.value();
        }
        return null;
    }

    /**
     * Alias-only lookup. Returns null if the alias is not registered.
     *
     * @param alias the type alias (e.g. "cite:pubmed")
     * @return the resolved class, or null if not found
     */
    public static Class<?> resolveAlias(String alias) {
        return aliasToClass.get(alias);
    }

    /**
     * Resolves an alias with hierarchical fallback. Tries exact match first,
     * then strips the last colon-separated segment recursively until a match is found.
     *
     * <p>Example: {@code resolveAliasWithFallback("link:cite:pubmed:full")} tries:
     * <ol>
     *   <li>{@code "link:cite:pubmed:full"} - exact match</li>
     *   <li>{@code "link:cite:pubmed"} - parent level</li>
     *   <li>{@code "link:cite"} - grandparent level</li>
     *   <li>{@code "link"} - root level</li>
     * </ol>
     *
     * @param alias the hierarchical alias to resolve
     * @return the resolved class, or null if no level matches
     */
    public static Class<?> resolveAliasWithFallback(String alias) {
        if (alias == null) {
            return null;
        }
        String current = alias;
        while (true) {
            Class<?> clazz = aliasToClass.get(current);
            if (clazz != null) {
                if (!current.equals(alias)) {
                    log.info("TypeAliasRegistry: resolved \"{}\" via fallback to \"{}\"", alias, current);
                }
                return clazz;
            }
            int lastColon = current.lastIndexOf(':');
            if (lastColon < 0) {
                return null;
            }
            current = current.substring(0, lastColon);
        }
    }

    /**
     * Returns whether the registry has been initialized.
     */
    public static boolean isInitialized() {
        return initialized;
    }
}
