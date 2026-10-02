/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package scannerbad.dynstring;

import ai.redouble.nucleo.prompt.*;

/**
 * Scanner-refusal fixture: {@code @DynamicPrompt} on a String constant - a String cannot
 * vary, so the declaration is meaningless.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-18)
 */
public final class DynamicStringField {
    @DynamicPrompt("scannerbad.dynstring")
    public static final String TEXT = "a string cannot vary";
}
