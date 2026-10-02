/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors;

/**
 * A planned operation that has not been built yet. Distinct in meaning from a bare
 * {@link UnsupportedOperationException}, which says an implementation will never
 * support the operation by design: this says the surface is intended and simply not
 * implemented, so the first real caller gets a loud, honest signal instead of a
 * silent no-op, an empty result, or a "never" it would wrongly design around.
 * Extends {@link UnsupportedOperationException} so existing handlers keep working.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-19)
 */
public class NotImplementedException extends UnsupportedOperationException {

    public NotImplementedException(String message) {
        super(message);
    }
}
