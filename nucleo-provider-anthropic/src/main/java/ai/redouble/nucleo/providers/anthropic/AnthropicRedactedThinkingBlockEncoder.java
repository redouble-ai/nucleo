/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.anthropic;

import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.llm.encode.*;
import com.anthropic.models.messages.*;

/**
 * Anthropic native redacted-thinking: replays the opaque payload verbatim on later turns.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-18)
 */
public class AnthropicRedactedThinkingBlockEncoder extends RedactedThinkingBlockEncoder<ContentBlockParam> {
    @Override
    public ContentBlockParam encode(ContentBlocks.ContentBlock block) {
        ContentBlocks.RedactedThinkingBlock rt = (ContentBlocks.RedactedThinkingBlock) block;
        return ContentBlockParam.ofRedactedThinking(RedactedThinkingBlockParam.builder().data(rt.data()).build());
    }
}
