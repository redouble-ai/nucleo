/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package scannerbad.both;

import ai.redouble.nucleo.prompt.*;

/**
 * Scanner-refusal fixture: one declaration wearing both annotations - the author must
 * pick one promise.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-18)
 */
public final class BothAnnotations {
    @StaticPrompt("scannerbad.both")
    @DynamicPrompt("scannerbad.both")
    public static final String TEXT = "which is it";
}
