/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm.encode;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;


/**
 * Default {@link ThinkingBlock} encoder: nothing on the wire. Most providers have no thinking channel
 * and drop replayed thinking; Anthropic subclasses to replay it verbatim (signature round-trip).
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-18)
 */
public class ThinkingBlockEncoder<B> extends BlockEncoder<B> {
    @Override
    public B encode(ContentBlock block) {
        return null;
    }
}
