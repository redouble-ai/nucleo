/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.annotations;

import com.fasterxml.jackson.databind.node.*;

/**
 * One annotation a deployment can override, with what the code declares for it: a line of
 * the listing {@link Annotations#keysOf} gives.
 *
 * @param key        the key a resolver is asked for
 * @param value      the {@code value} the code declares, one entry per line when it is a list
 *                   of strings; null when the annotation has no {@code value}
 * @param attributes the annotation's other attributes as the code declares them; null when
 *                   it has no other attribute
 * @author Andrey Santrosyan
 * @since 0.1.5 (2026-11-22)
 */
public record AnnotationKey(String key, String value, ObjectNode attributes) {
}
