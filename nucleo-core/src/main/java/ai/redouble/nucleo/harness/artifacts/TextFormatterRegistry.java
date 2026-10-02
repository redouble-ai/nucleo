/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts;

import ai.redouble.nucleo.harness.schema.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Resolves the deterministic text form of any artifact. Formatters register per
 * artifact class; resolution walks the artifact's class hierarchy to the nearest
 * registered formatter, mirroring how the {@code @TypeAlias} hierarchy expresses
 * IS-A. Artifacts with no registered formatter fall back to their canonical full
 * JSON form ({@link NucleoJsonSerializer#write}), which is deterministic.
 *
 * <p>This is the data half of a system response: the LLM's prose points at
 * artifacts by ref, and at the persistence boundary the referenced artifacts are
 * rendered through this registry - so the data portion of every response is a
 * deterministic function of the artifacts, never of the model.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-12)
 */
public final class TextFormatterRegistry {
    private TextFormatterRegistry() {
    }

    private static final Map<Class<?>, ArtifactTextFormatter<?>> FORMATTERS = new ConcurrentHashMap<>();

    static {
        // Direct put: ListArtifact's wildcard parameterization does not fit the
        // generic register signature; dispatch in format() is unchecked anyway.
        FORMATTERS.put(ListArtifact.class, new ListArtifactTextFormatter());
    }

    /**
     * Registers a formatter for an artifact class. Subclasses without their own
     * formatter resolve to the nearest registered ancestor.
     */
    public static <T extends Artifact> void register(Class<T> artifactClass, ArtifactTextFormatter<? super T> formatter) {
        FORMATTERS.put(artifactClass, formatter);
    }

    /**
     * Removes the formatter registered for exactly this class.
     */
    public static void unregister(Class<? extends Artifact> artifactClass) {
        FORMATTERS.remove(artifactClass);
    }

    /**
     * The nearest registered formatter in the class's hierarchy, or null when none is
     * registered and {@link #format} would fall back to JSON.
     */
    public static ArtifactTextFormatter<?> find(Class<?> artifactClass) {
        Class<?> clazz = artifactClass;
        while (clazz != null && Artifact.class.isAssignableFrom(clazz)) {
            ArtifactTextFormatter<?> formatter = FORMATTERS.get(clazz);
            if (formatter != null) {
                return formatter;
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    /**
     * Renders an artifact through the nearest registered formatter in its class
     * hierarchy, falling back to canonical full JSON.
     */
    @SuppressWarnings("unchecked")
    public static String format(Artifact artifact) {
        if (artifact == null) {
            return null;
        }
        ArtifactTextFormatter<?> formatter = find(artifact.getClass());
        if (formatter != null) {
            return ((ArtifactTextFormatter<Artifact>) formatter).format(artifact);
        }
        return NucleoJsonSerializer.write(artifact);
    }
}
