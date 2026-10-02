/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package scannerbad.methodmarker;

import ai.redouble.nucleo.prompt.*;
import ai.redouble.nucleo.prompt.sources.*;

/**
 * Scanner-refusal fixture: a {@code @DynamicPrompt} method returning a
 * {@link StaticPromptSource} - the marker contradicts the annotation's promise.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-18)
 */
public final class DynamicMethodStaticSource {
    @DynamicPrompt("scannerbad.methodmarker")
    public static StaticPromptSource supply() {
        return new StaticTextSource("actually static");
    }
}
