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
     * Light compaction - summarize tool results only.
     * Verbose tool output shrinks to concise summaries; every other message,
     * and every non-compactable tool result, is preserved verbatim.
     */
    LIGHT,
    /**
     * Moderate compaction - whole segments collapse into summaries.
     * Each compactable segment (the outgoing messages between assistant responses)
     * becomes one summary message, with age-weighted detail: the older the segment,
     * the terser its summary. Non-compactable messages survive verbatim.
     */
    MODERATE,
    /**
     * Aggressive compaction - keep only essential information.
     * Every compactable message collapses into one comprehensive summary.
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
