/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation.compaction;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.tokens.*;
import ai.redouble.nucleo.util.*;
import org.slf4j.*;

/**
 * Manages context window limits and triggers compaction when needed.
 *
 * <p>Two numbers govern it, each resolved from three levels and documented here because
 * neither is derivable from the provider's limits:
 * <ul>
 *   <li><b>The comfort window</b> - the prompt size past which a model's answers are observed
 *       to degrade, well below its hard {@code max_context_tokens}. An empirical number we
 *       own. Resolution: the thinker's override, else the catalog entry's
 *       {@code comfort_context_tokens}, else {@link #DEFAULT_COMFORT_CONTEXT_TOKENS}, which
 *       is the point past which Anthropic models were seen to misbehave when the number was
 *       set and stands until an entry says otherwise. The effective limit is the smaller of
 *       the comfort window and the model's hard context.
 *   <li><b>The compaction trigger</b> - the fraction of the effective limit at which
 *       compaction starts. A ratio with no model in it, so it has no catalog level.
 *       Resolution: the thinker's override, else {@link #DEFAULT_COMPACTION_TRIGGER}.
 * </ul>
 * Every decision reads one {@link TokenLedger}, the model's estimate of the conversation
 * ({@link ModelSpec#estimate}), and reads its input alone: compaction shrinks input and
 * nothing else, so the room a call needs for its answer is no part of its arithmetic. The
 * trigger compares the input to the effective limit times the trigger; the absolute fit check
 * compares it to the model's hard context, and only that failure is an overflow. A caller
 * that already holds the estimate passes it in, so one estimate serves one decision.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-15)
 */
public class ContextWindowManager {
    private static final Logger log = LoggerFactory.getLogger(ContextWindowManager.class);
    /** Framework comfort window, applied when neither the thinker nor the catalog entry declares one. */
    public static final int DEFAULT_COMFORT_CONTEXT_TOKENS = 128_000;
    /** Framework compaction trigger, applied when the thinker declares none. */
    public static final double DEFAULT_COMPACTION_TRIGGER = 0.92;
    private final ModelSpec model;
    private final Identifiable parent;
    private final int comfortContextTokens;
    private final double compactionTrigger;

    /**
     * @param model           the spec accounting runs under
     * @param parent          lineage for the compaction jobs
     * @param comfortOverride the thinker's comfort window, or null to resolve through the entry and the default
     * @param triggerOverride the thinker's compaction trigger in (0, 1], or null for the default
     */
    public ContextWindowManager(ModelSpec model, Identifiable parent, Integer comfortOverride, Double triggerOverride) {
        if (comfortOverride != null && comfortOverride < 1) {
            throw new IllegalArgumentException("A comfort context window must be at least 1 token, got " + comfortOverride);
        }
        if (triggerOverride != null && (triggerOverride <= 0.0 || triggerOverride > 1.0)) {
            throw new IllegalArgumentException("A compaction trigger is a fraction in (0, 1], got " + triggerOverride);
        }
        this.model = model;
        this.parent = parent;
        Integer entryComfort = model.getComfortContextTokens();
        this.comfortContextTokens = comfortOverride != null ? comfortOverride
                : entryComfort != null ? entryComfort : DEFAULT_COMFORT_CONTEXT_TOKENS;
        this.compactionTrigger = triggerOverride != null ? triggerOverride : DEFAULT_COMPACTION_TRIGGER;
    }

    /** The smaller of the resolved comfort window and the model's hard context. */
    public int effectiveLimit() {
        return Math.min(comfortContextTokens, model.getMaxContextTokens());
    }

    /** The model's estimate of the conversation as it stands. */
    public TokenLedger estimate(ConversationContext context) {
        return model.estimate(context);
    }

    /**
     * Whether the conversation needs compaction: compactable, and its estimated input above
     * the effective limit times the trigger.
     *
     * @param context  the conversation
     * @param estimate the model's estimate of it, as the caller holds it
     */
    public boolean needsCompaction(ConversationContext context, TokenLedger estimate) {
        if (!context.isCompactable()) {
            return false;
        }
        return estimate.inputTokens() > effectiveLimit() * compactionTrigger;
    }

    /**
     * Ensures the conversation fits within acceptable limits, compacting if needed. The ladder
     * walks LIGHT, MODERATE and AGGRESSIVE as best-effort rungs (a failed summary keeps the
     * originals and the walk continues), then MAXIMUM, whose failures propagate loudly instead
     * of degrading. Each rung is measured once, by a fresh estimate of the compacted
     * conversation.
     *
     * @TODO gh-1: MAXIMUM currently performs the same collapse as AGGRESSIVE, so this
     * method cannot yet GUARANTEE a fit - a conversation whose non-compactable messages
     * alone exceed the hard limit still ends in the overflow refusal below.
     *
     * @param context  the conversation to check and potentially compact
     * @param estimate the model's estimate of it, as the caller holds it
     * @return the original conversation if it fits, or a compacted version
     * @throws ContextOverflowException if the conversation cannot be made to fit
     */
    public ConversationContext ensureFits(ConversationContext context, TokenLedger estimate) throws ContextOverflowException {
        if (!needsCompaction(context, estimate)) {
            return context;
        }
        log.info("Context needs compaction: {} tokens exceeds threshold", Formats.compactNumber(estimate.inputTokens()));
        CompactionLevel[] levels = {
                CompactionLevel.LIGHT, CompactionLevel.MODERATE, CompactionLevel.AGGRESSIVE, CompactionLevel.MAXIMUM};
        ConversationContext current = context;
        TokenLedger measured = estimate;
        ContextCompactor compactor = newCompactor();
        for (CompactionLevel level : levels) {
            current = compactor.compact(current, level);
            measured = estimate(current);
            if (!needsCompaction(current, measured)) {
                log.info("Context compacted with {} to {} tokens", level, Formats.compactNumber(measured.inputTokens()));
                return current;
            }
            log.info("After {} compaction: {} tokens (still too large)", level, Formats.compactNumber(measured.inputTokens()));
        }
        // Above the comfort window even now: acceptable as long as the input fits the model's
        // hard context. Whether the call's answer also fits is the pre-flight's question.
        int absoluteLimit = model.getMaxContextTokens();
        if (measured.inputTokens() <= absoluteLimit) {
            log.warn("Context {} exceeds optimal but fits model limit", Formats.compactNumber(measured.inputTokens()));
            return current;
        }
        throw new ContextOverflowException(
                "Cannot fit context even with maximum compaction. Size: " + Formats.compactNumber(measured.inputTokens())
                + ", limit: " + Formats.compactNumber(absoluteLimit) + "; by part: " + measured.inputByPart());
    }

    /** The compactor the ladder runs. Package-private so tests can drive the ladder deterministically. */
    ContextCompactor newCompactor() {
        return new LLMContextCompactor(parent);
    }

    /**
     * The estimated input as a share of the effective limit.
     *
     * @param estimate the model's estimate of the conversation
     * @return usage percentage (0.0 to 1.0+)
     */
    public double getUsagePercentage(TokenLedger estimate) {
        return estimate.inputTokens() / (double)effectiveLimit();
    }
}
