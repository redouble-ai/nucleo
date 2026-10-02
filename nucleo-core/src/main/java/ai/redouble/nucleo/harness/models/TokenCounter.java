/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.conversation.*;

/**
 * Counts tokens for text, messages, and multimodal content blocks; conversation-level
 * totals are the conversation's own ({@code ConversationContext.getTotalTokens}).
 * <p>
 * Obtain an instance through {@link TokenizerFactory#forModel(ModelSpec)}. The
 * factory chooses the appropriate counter implementation per model and can be
 * replaced wholesale or extended with custom registrations via
 * {@link TokenizerFactory#register(ModelSpec, java.util.function.Supplier)}.
 * <p>
 * Implementations MUST be safe for concurrent use across virtual threads.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-16)
 */
public interface TokenCounter {
    int countTokens(String text);

    int countTokens(Message message);

    int countImageTokens(ImageBlock image);

    int countFileTokens(FileBlock file);
}
