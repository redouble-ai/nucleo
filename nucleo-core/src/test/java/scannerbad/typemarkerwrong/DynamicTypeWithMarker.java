/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package scannerbad.typemarkerwrong;

import ai.redouble.nucleo.prompt.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

/**
 * Scanner-refusal fixture: a {@code @DynamicPrompt} TYPE that carries the
 * {@link StaticPromptSource} marker its annotation forbids.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-18)
 */
@DynamicPrompt("scannerbad.typemarkerwrong")
public class DynamicTypeWithMarker implements StaticPromptSource {
    @Override
    public JsonNode produce(String key) {
        return TextNode.valueOf("never registered");
    }
}
