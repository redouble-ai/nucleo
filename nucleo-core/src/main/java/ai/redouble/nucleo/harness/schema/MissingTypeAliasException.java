/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

import ai.redouble.nucleo.harness.artifacts.*;

import java.util.*;

/**
 * Thrown during {@link TypeAliasRegistry} initialization when concrete
 * {@link Artifact} classes are found without a required {@code @TypeAlias} annotation.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-21)
 */
public class MissingTypeAliasException extends RuntimeException {
    private final Set<Class<?>> offendingClasses;
    public MissingTypeAliasException(Set<Class<?>> offendingClasses) {
        super(formatMessage(offendingClasses));
        this.offendingClasses = Collections.unmodifiableSet(offendingClasses);
    }
    public Set<Class<?>> getOffendingClasses() {
        return offendingClasses;
    }
    private static String formatMessage(Set<Class<?>> classes) {
        StringBuilder sb = new StringBuilder();
        sb.append("Artifact classes missing @TypeAlias:\n");
        for (Class<?> clazz : classes) {
            sb.append("  - ").append(clazz.getName()).append("\n");
        }
        return sb.toString();
    }
}
