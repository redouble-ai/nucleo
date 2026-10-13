/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.tokens.*;
import com.knuddels.jtokkit.api.*;

/**
 * How an endpoint speaking OpenAI's APIs counts a conversation before the call. OpenAI's
 * current chat models tokenize with {@code o200k_base}, which JTokkit ships, so OpenAI's own
 * endpoints count exactly; an OpenAI-compatible endpoint serves models of other families,
 * whose tokenizers are not known here, and counts with the provider-neutral encoding. Tool
 * calls, tool results and tool definitions go as native JSON and are counted as their text
 * form. An image costs what OpenAI publishes, 85 tokens plus 170 per 512-pixel tile after
 * scaling ({@link OpenAITokenCounter}).
 *
 * <p>A thinking block is never sent, so it costs nothing. A reasoning item the Responses API
 * returned ({@link RedactedThinkingBlock}) is replayed on later turns and counted as the JSON it
 * carries, which errs on the side of reserving too much.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-09)
 */
public class OpenAIContextEstimator extends AbstractContextEstimator {
    /** The counter for OpenAI's own chat models: exact {@code o200k_base} text and OpenAI's image rule. */
    public static final OpenAITokenCounter O200K_COUNTER = new OpenAITokenCounter();
    /** For OpenAI's own endpoints, direct or on Azure. */
    public static final OpenAIContextEstimator OPENAI = new OpenAIContextEstimator(O200K_COUNTER);
    /** For an OpenAI-compatible endpoint serving models of unknown tokenization. */
    public static final OpenAIContextEstimator COMPATIBLE = new OpenAIContextEstimator(TiktokenCounter.DEFAULT);

    public OpenAIContextEstimator(TokenCounter counter) {
        super(new OpenAIContentFormatter(), counter);
    }

    @Override
    protected int countBlock(ContentBlock block, TurnPlace place) {
        return switch (block) {
            case RedactedThinkingBlock rt -> counter().countTokens(rt.data());
            default -> super.countBlock(block, place);
        };
    }
}
