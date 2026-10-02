/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm.encode;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;


/**
 * Default {@link FileBlock} encoder: a text placeholder naming the file. Providers that can carry
 * files or convert them (Anthropic PDFs, OpenAI image-compatible files) subclass and override.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-18)
 */
public class FileBlockEncoder<B> extends BlockEncoder<B> {
    @Override
    public B encode(ContentBlock block) {
        return textWrapper.wrap("[file " + ((FileBlock) block).filename() + "]");
    }
}
