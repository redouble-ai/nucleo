/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package scannerbad.fieldmarker;

import ai.redouble.nucleo.prompt.*;
import com.fasterxml.jackson.databind.node.*;

/**
 * Scanner-refusal fixture: an {@code @StaticPrompt} field holding a plain
 * {@link PromptSource} lambda without the static marker its annotation promises.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-18)
 */
public final class StaticFieldDynamicSource {
    @StaticPrompt("scannerbad.fieldmarker")
    public static final PromptSource SRC = key -> TextNode.valueOf("not actually static");
}
