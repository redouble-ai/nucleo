/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation.compaction;

import ai.redouble.nucleo.harness.conversation.*;

/**
 * Interface for compacting conversation contexts to reduce token count.
 *
 * <p>Compaction rewrites the context's MESSAGE list in place - the returned context is
 * the one passed in. The main objective, declared tools and artifact registry are never
 * touched: compaction is a message-history operation.
 *
 * <p>{@link CompactionLevel#LIGHT}, {@link CompactionLevel#MODERATE} and
 * {@link CompactionLevel#AGGRESSIVE} are best-effort: a summary that fails keeps the
 * original messages, and the caller's ladder moves on. {@link CompactionLevel#MAXIMUM}
 * is not - its failures propagate as LLM-readable exceptions.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-15)
 */
public interface ContextCompactor {
    /**
     * Compacts a conversation context to reduce token count.
     *
     * <p>Replaces compactable messages with summaries per the level, preserving
     * semantic meaning and conversation continuity; non-compactable messages
     * ({@link ai.redouble.nucleo.harness.conversation.Message#isCompactable()})
     * survive verbatim.
     *
     * @param context the context to compact, its message list rewritten in place
     * @param level how aggressively to compact
     * @return the same context, its message list compacted
     */
    ConversationContext compact(ConversationContext context, CompactionLevel level);
}