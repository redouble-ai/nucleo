/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.anthropic;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.tokens.*;
import com.knuddels.jtokkit.api.*;

/**
 * How Anthropic counts a conversation before the call. Claude's tokenizer is not public, so
 * text is counted with {@code cl100k_base} at the density Anthropic's own usage reports show
 * for it, a tenth more tokens than the encoding says. Tool calls, tool results and tool
 * definitions go to Anthropic as native blocks and are counted as their text form, the name or
 * id with the JSON they carry. An image costs at least 1600 tokens and a PDF 450 per KB.
 *
 * <p>Thinking blocks, redacted or not, are replayed on every assistant turn
 * ({@link AnthropicThinkingBlockEncoder}) and Anthropic bills only the newest assistant turn's,
 * stripping the earlier ones, so a thinking block counts on the last assistant turn and nowhere
 * else.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-09)
 */
public class AnthropicContextEstimator extends AbstractContextEstimator {
    /** Claude tokens per cl100k token, measured against Anthropic's usage reports. */
    public static final double CLAUDE_DENSITY = 1.10;
    /** The floor one image costs on Claude. */
    public static final int IMAGE_BASE_TOKENS = 1600;
    /** What a KB of PDF costs on Claude. */
    public static final int PDF_TOKENS_PER_KB = 450;
    /** The density every Anthropic-served entry counts text with, for prompts that are not a conversation. */
    public static final TiktokenCounter COUNTER = new TiktokenCounter(EncodingType.CL100K_BASE, CLAUDE_DENSITY, IMAGE_BASE_TOKENS, 0, PDF_TOKENS_PER_KB);
    public static final AnthropicContextEstimator INSTANCE = new AnthropicContextEstimator();

    public AnthropicContextEstimator() {
        super(new AnthropicContentFormatter(), COUNTER);
    }

    @Override
    protected int countBlock(ContentBlock block, TurnPlace place) {
        return switch (block) {
            case ThinkingBlock th -> place == TurnPlace.LAST_ASSISTANT ? counter().countTokens(th.text()) : 0;
            case RedactedThinkingBlock rt -> place == TurnPlace.LAST_ASSISTANT ? counter().countTokens(rt.data()) : 0;
            default -> super.countBlock(block, place);
        };
    }
}
