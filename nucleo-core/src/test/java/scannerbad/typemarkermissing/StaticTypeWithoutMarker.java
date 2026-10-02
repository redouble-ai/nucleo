/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package scannerbad.typemarkermissing;

import ai.redouble.nucleo.prompt.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

/**
 * Scanner-refusal fixture: an {@code @StaticPrompt} TYPE that implements only
 * {@link PromptSource}, not the {@link StaticPromptSource} marker its annotation promises.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-18)
 */
@StaticPrompt("scannerbad.typemarkermissing")
public class StaticTypeWithoutMarker implements PromptSource {
    @Override
    public JsonNode produce(String key) {
        return TextNode.valueOf("never registered");
    }
}
