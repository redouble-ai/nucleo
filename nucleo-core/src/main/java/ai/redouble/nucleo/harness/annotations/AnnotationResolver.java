/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.annotations;

import java.util.*;

/**
 * Where a deployment keeps what it wants an annotation to say in place of the code: a
 * database table, a file, a service. Set once with {@link Annotations#setResolver}.
 *
 * <p>It is asked for the keys of one class at a time and answers with an entry for each key
 * it holds. A key it does not hold is left out of the answer, and that annotation reads as
 * the code wrote it. What it answers is kept until {@link Annotations#changed()} is called,
 * so it is not asked again for a class it has answered for.
 *
 * @author Andrey Santrosyan
 * @since 0.1.5 (2026-11-22)
 */
@FunctionalInterface
public interface AnnotationResolver {
    /**
     * @param keys the keys of one class, each in the form {@link Annotations#keyOf} writes
     * @return what is stored, by key; never null, and without an entry for a key that has none
     */
    Map<String, AnnotationOverride> resolve(Collection<String> keys);
}
