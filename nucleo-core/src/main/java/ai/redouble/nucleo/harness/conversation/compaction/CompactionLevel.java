/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation.compaction;


/**
 * Defines the aggressiveness of context/message compaction.
 * Higher levels remove more content but preserve less detail.
 *
 * <p>The first three levels are BEST-EFFORT: a summary that fails is not fatal - the
 * original messages are kept and the ladder moves on to the next level. MAXIMUM is not:
 * it is the last line before {@link ai.redouble.nucleo.harness.conversation.ContextOverflowException},
 * so its failures surface loudly instead of degrading.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-22)
 */
public enum CompactionLevel {
    /**
     * Light compaction - summarize tool results only. Every tool result of an exchange that
     * may be compacted shrinks to a summary inside its own block; every other message, and
     * every exchange pinned by a non-compactable message, is preserved verbatim.
     */
    LIGHT,
    /**
     * Moderate compaction - whole exchanges collapse into summaries. Each exchange that may
     * be compacted (an assistant turn and the outgoing turns that answer it) becomes one
     * summary message, with age-weighted detail: the older the exchange, the terser its
     * summary. Non-compactable messages survive verbatim, with their exchange.
     */
    MODERATE,
    /**
     * Aggressive compaction - keep only essential information. Every compactable message
     * before the newest exchange collapses into one comprehensive summary, behind the
     * non-compactable messages kept verbatim; the newest exchange is summarized on its own
     * when it may be compacted and carries tool results, and stays as it is otherwise.
     * May lose nuance but preserves core meaning.
     */
    AGGRESSIVE,
    /**
     * Maximum compaction - the level whose outcome must be a guaranteed fit, so that
     * {@code ContextWindowManager.ensureFits} can promise what its name says. Unlike
     * the best-effort levels below it, a MAXIMUM failure propagates loudly.
     *
     * @TODO gh-1: today MAXIMUM runs the same collapse as AGGRESSIVE and guarantees
     * nothing beyond it - the real implementation (what it may touch that AGGRESSIVE
     * may not) is an open design question tracked there.
     */
    MAXIMUM
}
