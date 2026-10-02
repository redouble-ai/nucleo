/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm.encode;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;


/**
 * Default {@link TextBlock} encoder: the block's text, wrapped into the provider's native text block.
 * An empty (or null) text block produces no wire content.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-18)
 */
public class TextBlockEncoder<B> extends BlockEncoder<B> {
    @Override
    public B encode(ContentBlock block) {
        String text = ((TextBlock) block).text();
        return text == null || text.isEmpty() ? null : textWrapper.wrap(text);
    }
}
