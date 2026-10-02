/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm.encode;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;


/**
 * Default {@link RedactedThinkingBlock} encoder: nothing on the wire. Anthropic subclasses to replay
 * the opaque payload verbatim on later turns.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-18)
 */
public class RedactedThinkingBlockEncoder<B> extends BlockEncoder<B> {
    @Override
    public B encode(ContentBlock block) {
        return null;
    }
}
