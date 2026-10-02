/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;


/**
 * Semantic signal for investigation depth, propagated top-down through thinker hierarchies.
 * The LLM at each level interprets this to calibrate its effort - it is NOT a mechanical
 * constraint on iterations or tool availability.
 *
 * <p>{@code ThinkerInput} initializes this to {@link #STANDARD}, so depth is never null
 * at read sites. Callers may override before submitting a thinker.
 *
 * <p>Depth is also the single vocabulary for thinking effort: a model's extended-thinking
 * budget scales from it ({@link ModelSpec#getThinkingBudget(Depth)}), and adaptive models
 * receive it as their effort level. {@link #IMMEDIATE} attaches no thinking.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-21)
 */
public enum Depth {
    /**
     * Answer from knowledge or NO tool use
     */
    IMMEDIATE,
    /**
     * Answer from knowledge or with minimal tool use - avoid invoking heavy sub-agents
     */
    QUICK,
    /**
     * Moderate investigation, use tools as needed but prefer efficiency
     */
    STANDARD,
    /**
     * Deep analysis, invoke all relevant sub-agents for complete coverage
     */
    THOROUGH,
    /**
     * Ultra-deep analysis, invoke all relevant sub-agents for complete, comprehensive coverage
     */
    ULTRA_THOROUGH;

    /**
     * Returns the lower of two depths (closer to IMMEDIATE).
     */
    public static Depth min(Depth a, Depth b) {
        return a.ordinal() <= b.ordinal() ? a : b;
    }
}
