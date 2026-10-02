/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package scannerbad.nonfinal;

import ai.redouble.nucleo.prompt.*;

/**
 * Scanner-refusal fixture: an annotated prompt field that is not final - a mutable
 * declaration cannot promise anything.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-18)
 */
public final class NonFinalField {
    @StaticPrompt("scannerbad.nonfinal")
    public static String TEXT = "mutable";
}
