/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.tokens;

import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.llm.encode.*;
import ai.redouble.nucleo.harness.models.*;

import java.util.*;
import java.util.function.*;

/**
 * The estimator every provider extends: one walk over the conversation as the provider's
 * client renders it, counting each part with the provider's counter. The provider module
 * passes its formatter and its density here and overrides {@link #countBlock} for the block
 * kinds it bills differently from the text the generic encoders send, which is what this
 * class counts by default.
 *
 * <p>The walk renders through {@link ConversationContext#render}, the pure rendering the
 * client's own preparation also uses, so the count is of the payload the wire carries:
 * system text, tool definitions, every turn with its response instructions, the registry
 * section. The output side is the conversation's reserve for the next call, the declared
 * answer plus the depth's thinking budget clamped at the model's ceiling, exactly what the
 * wire will ask for; the ledger resolves it when a reader asks for it, so counting the input
 * alone asks nothing of the seat's declarations.
 *
 * <p>By default a thinking block costs nothing, because the generic encoders do not send
 * one; a provider that replays thinking overrides {@link #countBlock} and uses the
 * {@link TurnPlace} to apply its billing rule.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-09)
 */
public abstract class AbstractContextEstimator implements ContextEstimator {
    private final ContentFormatter formatter;
    private final TokenCounter counter;

    /**
     * Both are required; a null for either is refused.
     *
     * @param formatter the formatter the provider's client renders with; the rendering is counted
     * @param counter   the density of the provider's tokenizer
     */
    protected AbstractContextEstimator(ContentFormatter formatter, TokenCounter counter) {
        if (formatter == null || counter == null) {
            throw new IllegalArgumentException("An estimator renders with a formatter and counts with a counter; both are required");
        }
        this.formatter = formatter;
        this.counter = counter;
    }

    /** The density this estimator counts with; {@code ClientProvider.tokenCounter} returns the same. */
    public TokenCounter counter() {
        return counter;
    }

    @Override
    public TokenLedger estimate(ConversationContext conversation, ModelSpec spec) {
        // Whether a native thinking block is active changes the answer instructions the handler
        // renders, so the estimate renders with the same decision the client will make. A
        // conversation with no seat yet declares no depth and books no reasoning; its input is
        // counted as the call a seat books none would send.
        Depth depth = conversation.declaredDepth();
        boolean thinkingActive = depth != null && ThinkingMode.thinkingActive(spec, depth);
        PreparedConversation prepared = conversation.render(formatter, thinkingActive);
        EnumMap<ContextPart, Integer> parts = new EnumMap<>(ContextPart.class);
        parts.put(ContextPart.OBJECTIVE, counter.countTokens(prepared.systemText()));
        int definitions = 0;
        for (ToolDefinitionBlock td : prepared.toolDefinitions()) {
            definitions += counter.countTokens(ToolDefinitionBlockEncoder.renderText(td));
        }
        parts.put(ContextPart.TOOL_DEFINITIONS, definitions);
        List<ProcessedMessageData> turns = prepared.turns();
        int conversationTurns = prepared.registrySection() != null ? turns.size() - 1 : turns.size();
        int lastAssistant = -1;
        for (int i = 0; i < conversationTurns; i++) {
            if (turns.get(i).role() == TurnRole.ASSISTANT) {
                lastAssistant = i;
            }
        }
        int messages = 0;
        for (int i = 0; i < conversationTurns; i++) {
            ProcessedMessageData turn = turns.get(i);
            TurnPlace place = turn.role() == TurnRole.ASSISTANT
                    ? (i == lastAssistant ? TurnPlace.LAST_ASSISTANT : TurnPlace.EARLIER_ASSISTANT)
                    : TurnPlace.USER;
            for (ContentBlock block : turn.contentBlocks()) {
                messages += countBlock(block, place);
            }
        }
        parts.put(ContextPart.MESSAGES, messages);
        parts.put(ContextPart.ARTIFACT_REGISTRY, prepared.registrySection() != null ? counter.countTokens(prepared.registrySection()) : 0);
        return newLedger(spec, parts, () -> conversation.outputReserve(spec));
    }

    /**
     * What one rendered block costs on this provider. The default is the text the generic
     * encoders send for it, under the counter, with images and files through the counter's
     * own rules and thinking blocks at nothing. A provider overrides for the block kinds it
     * encodes or bills differently.
     */
    protected int countBlock(ContentBlock block, TurnPlace place) {
        return switch (block) {
            case ImageBlock ib -> counter.countImageTokens(ib);
            case FileBlock fb -> counter.countFileTokens(fb);
            case TextBlock tb -> counter.countTokens(tb.text());
            case JsonBlock jb -> counter.countTokens(jb.json());
            case PojoBlock pb -> counter.countTokens(ModelText.render(pb));
            case ToolDefinitionBlock td -> counter.countTokens(ModelText.render(td));
            case ToolUseBlock tu -> counter.countTokens(ModelText.render(tu));
            case ToolResultBlock tr -> counter.countTokens(ModelText.render(tr));
            case ThinkingBlock th -> 0;
            case RedactedThinkingBlock rt -> 0;
            case SkillBlock sb -> counter.countTokens(ModelText.render(sb));
        };
    }

    /** The ledger class this estimator answers with; a provider returns its own subclass. */
    protected TokenLedger newLedger(ModelSpec spec, Map<ContextPart, Integer> inputByPart, IntSupplier outputReserve) {
        return new DefaultTokenLedger(spec, inputByPart, outputReserve);
    }
}
