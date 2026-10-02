/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.prompt.sources;

import ai.redouble.nucleo.prompt.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

/**
 * {@link StaticPromptSource} whose content is a fixed {@code String} captured at
 * construction time. Ignores the supplied key. The predominant source type, used by the
 * prompt scanner to wrap {@code @StaticPrompt}-annotated {@code static final String}
 * declarations.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-20)
 */
public class StaticTextSource implements StaticPromptSource {
    private final JsonNode content;

    public StaticTextSource(String text) {
        this.content = TextNode.valueOf(text);
    }

    @Override
    public JsonNode produce(String key) {
        return content;
    }
}
