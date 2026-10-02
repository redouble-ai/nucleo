/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.anthropic;

import ai.redouble.nucleo.harness.llm.encode.*;
import com.anthropic.models.messages.*;

/**
 * The one Anthropic text wrap: a string becomes a {@code ContentBlockParam.ofText}. Shared by every
 * Anthropic text-rendering encoder via the injected {@link TextWrapper} field.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-18)
 */
public class AnthropicTextWrapper implements TextWrapper<ContentBlockParam> {
    @Override
    public ContentBlockParam wrap(String text) {
        return ContentBlockParam.ofText(TextBlockParam.builder().text(text).build());
    }
}
