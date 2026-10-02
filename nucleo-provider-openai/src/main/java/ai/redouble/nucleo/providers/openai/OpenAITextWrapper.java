/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.harness.llm.encode.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.node.*;

/**
 * The one OpenAI text wrap: a string becomes a {@code {"type":"text","text":...}} content element.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-18)
 */
public class OpenAITextWrapper implements TextWrapper<ObjectNode> {
    @Override
    public ObjectNode wrap(String text) {
        ObjectNode o = NucleoJsonSerializer.createObjectNode();
        o.put("type", "text");
        o.put("text", text);
        return o;
    }
}
