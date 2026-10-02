/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation.compaction;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.models.*;
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
 * Compaction keeps a conversation under the effective limit; the absolute fit check keeps
 * the whole call - input plus the seat's declared output reserve - inside the model's hard
 * context, and only that failure is an overflow.
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

    /**
     * Checks if the context needs compaction.
     *
     * @param context the context to check
     * @return true if compaction is needed
     */
    public boolean needsCompaction(ConversationContext context) {
        // Never compact non-compactable conversations
        if (!context.isCompactable()) {
            return false;
        }
        int total = context.getTotalTokens(model);
        return total > effectiveLimit() * compactionTrigger;
    }

    /**
     * Ensures the context fits within acceptable limits, compacting if needed. The ladder
     * walks LIGHT, MODERATE and AGGRESSIVE as best-effort rungs (a failed summary keeps
     * the originals and the walk continues), then MAXIMUM, whose failures propagate
     * loudly instead of degrading.
     *
     * @TODO gh-1: MAXIMUM currently performs the same collapse as AGGRESSIVE, so this
     * method cannot yet GUARANTEE a fit - a conversation whose non-compactable messages
     * alone exceed the hard limit still ends in the overflow refusal below.
     *
     * @param context the context to check and potentially compact
     * @return the original context if it fits, or a compacted version
     * @throws ContextOverflowException if context cannot be made to fit
     */
    public ConversationContext ensureFits(ConversationContext context) throws ContextOverflowException {
        if (!needsCompaction(context)) {
            return context;
        }

        log.info("Context needs compaction: {} tokens exceeds threshold", Formats.compactNumber(context.getTotalTokens(model)));

        // Try progressive compaction
        CompactionLevel[] levels = {
                CompactionLevel.LIGHT, CompactionLevel.MODERATE, CompactionLevel.AGGRESSIVE, CompactionLevel.MAXIMUM};

        ConversationContext current = context;
        ContextCompactor compactor = newCompactor();
        for (CompactionLevel level : levels) {
            current = compactor.compact(current, level);

            if (!needsCompaction(current)) {
                log.info("Context compacted with {} to {} tokens", level, Formats.compactNumber(current.getTotalTokens(model)));
                return current;
            }

            log.info("After {} compaction: {} tokens (still too large)", level, Formats.compactNumber(current.getTotalTokens(model)));
        }

        // Above the comfort window even now: acceptable as long as the whole call - input plus
        // the seat's declared output reserve - still fits the model's hard context
        int absoluteLimit = absoluteLimit(current);
        if (current.getTotalTokens(model) <= absoluteLimit) {
            log.warn("Context {} exceeds optimal but fits model limit", Formats.compactNumber(current.getTotalTokens(model)));
            return current;
        }

        throw new ContextOverflowException(
                "Cannot fit context even with maximum compaction. Size: " + Formats.compactNumber(current.getTotalTokens(model)) +
                ", limit: " + Formats.compactNumber(absoluteLimit));
    }

    /**
     * The input the model's hard context leaves for this conversation once the seat's declared
     * output and thinking reserve is set aside - the same reserve the wire will carry.
     */
    public int absoluteLimit(ConversationContext context) {
        return model.getMaxContextTokens() - context.outputReserve(model);
    }

    /** The compactor the ladder runs. Package-private so tests can drive the ladder deterministically. */
    ContextCompactor newCompactor() {
        return new LLMContextCompactor(parent);
    }

    /**
     * Gets the current usage percentage of the context window.
     *
     * @param context the context to check
     * @return usage percentage (0.0 to 1.0+)
     */
    public double getUsagePercentage(ConversationContext context) {
        int total = context.getTotalTokens(model);
        return total / (double)effectiveLimit();
    }

    /**
     * Checks if the context is approaching limits and logs warnings.
     *
     * @param context the context to check
     */
    public void checkAndWarn(ConversationContext context) {
        int total = context.getTotalTokens(model);
        int effectiveLimit = effectiveLimit();
        double usage = total / (double)effectiveLimit;
        if (usage > 0.9) {
            log.warn("Context critically high: {} ({}/{})", String.format("%.0f%%", usage * 100), Formats.compactNumber(total), Formats.compactNumber(effectiveLimit));
        }
        else if (usage > 0.7) {
            log.info("Context usage: {} ({}/{})", String.format("%.0f%%", usage * 100), Formats.compactNumber(total), Formats.compactNumber(effectiveLimit));
        }
    }
}
