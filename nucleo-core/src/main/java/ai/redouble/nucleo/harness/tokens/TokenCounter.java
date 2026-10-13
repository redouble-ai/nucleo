/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.tokens;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;

/**
 * The density of one model's tokenizer: how many tokens a text, an image or a file costs on
 * it. The provider module that serves a model supplies its counter through
 * {@code ClientProvider.tokenCounter(ModelSpec)}, and code reaches it as
 * {@code ModelSpec.tokenCounter()}; a counter never sees a conversation, that is the
 * {@link ContextEstimator}'s job.
 *
 * <p>Implementations MUST be safe for concurrent use across virtual threads.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-16)
 */
public interface TokenCounter {
    int countTokens(String text);

    int countImageTokens(ImageBlock image);

    int countFileTokens(FileBlock file);
}
