/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package scannerbad.noctor;

import ai.redouble.nucleo.prompt.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

/**
 * Scanner-refusal fixture: an {@code @StaticPrompt} TYPE without the no-arg constructor
 * the scanner needs to instantiate it.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-18)
 */
@StaticPrompt("scannerbad.noctor")
public class NoNoArgCtor implements StaticPromptSource {
    private final String text;

    public NoNoArgCtor(String text) {
        this.text = text;
    }

    @Override
    public JsonNode produce(String key) {
        return TextNode.valueOf(text);
    }
}
