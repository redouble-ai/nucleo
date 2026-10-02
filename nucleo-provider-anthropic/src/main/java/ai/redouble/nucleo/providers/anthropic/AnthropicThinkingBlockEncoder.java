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
 * Anthropic native thinking: replays the assistant's thinking verbatim. The signature must round-trip
 * exactly or Anthropic 400s on the next turn.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-18)
 */
public class AnthropicThinkingBlockEncoder extends ThinkingBlockEncoder<ContentBlockParam> {
    @Override
    public ContentBlockParam encode(ContentBlocks.ContentBlock block) {
        ContentBlocks.ThinkingBlock th = (ContentBlocks.ThinkingBlock) block;
        return ContentBlockParam.ofThinking(ThinkingBlockParam.builder().thinking(th.text()).signature(th.signature()).build());
    }
}
