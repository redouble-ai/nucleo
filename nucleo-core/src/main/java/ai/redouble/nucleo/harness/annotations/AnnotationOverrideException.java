/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.annotations;

/**
 * A stored {@link AnnotationOverride} that its annotation cannot take: an attribute the
 * annotation does not have, a value of the wrong type, a {@code value} placed among the
 * attributes, or text a prompt guardrail refused. The message names the key and what the
 * annotation accepts, and repeats nothing that was stored.
 *
 * <p>It is a defect of the deployment's store, so it is unchecked and is never degraded to
 * the code's wording: a row that silently did nothing would be worse than one that fails.
 *
 * @author Andrey Santrosyan
 * @since 0.1.5 (2026-11-22)
 */
public class AnnotationOverrideException extends RuntimeException {
    private final String key;

    public AnnotationOverrideException(String key, String problem) {
        super("Stored override of " + key + " refused: " + problem);
        this.key = key;
    }

    public AnnotationOverrideException(String key, String problem, Throwable cause) {
        super("Stored override of " + key + " refused: " + problem, cause);
        this.key = key;
    }

    public String getKey() {
        return key;
    }
}
