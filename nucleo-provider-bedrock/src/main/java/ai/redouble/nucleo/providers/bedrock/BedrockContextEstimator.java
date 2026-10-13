/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.bedrock;

import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.tokens.*;
import com.knuddels.jtokkit.api.*;

/**
 * How Bedrock Converse counts a conversation before the call. Converse serves every vendor, so
 * the density follows the model family the wire id names: Claude at Claude's density, Cohere at
 * the density Bedrock's own token metrics show for it, anything else at the neutral encoding.
 * The Converse client renders tool calls, tool results and thinking the generic way, text for
 * the first two and nothing for the last, so the generic walk is the right count for them.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-09)
 */
public class BedrockContextEstimator extends AbstractContextEstimator {
    /** Claude tokens per cl100k token on Converse, the same density as on the Anthropic SDK. */
    public static final double CLAUDE_DENSITY = 1.10;
    /** The floor one image costs on Claude. */
    public static final int CLAUDE_IMAGE_BASE_TOKENS = 1600;
    /**
     * Cohere's tokenizer bills 10-17% more tokens than cl100k on mixed-language corpora,
     * measured against Bedrock's InputTokenCount metrics; 1.20 keeps declared budgets above
     * what the provider meters.
     */
    public static final double COHERE_DENSITY = 1.20;
    public static final TiktokenCounter CLAUDE_COUNTER = new TiktokenCounter(EncodingType.CL100K_BASE, CLAUDE_DENSITY,
            CLAUDE_IMAGE_BASE_TOKENS, 0, TiktokenCounter.DEFAULT_PDF_TOKENS_PER_KB);
    public static final TiktokenCounter COHERE_COUNTER = new TiktokenCounter(EncodingType.CL100K_BASE, COHERE_DENSITY,
            TiktokenCounter.DEFAULT_IMAGE_BASE_TOKENS, TiktokenCounter.DEFAULT_IMAGE_PER_TILE_TOKENS, TiktokenCounter.DEFAULT_PDF_TOKENS_PER_KB);
    private static final BedrockContextEstimator CLAUDE = new BedrockContextEstimator(CLAUDE_COUNTER);
    private static final BedrockContextEstimator COHERE = new BedrockContextEstimator(COHERE_COUNTER);
    private static final BedrockContextEstimator DEFAULT = new BedrockContextEstimator(TiktokenCounter.DEFAULT);

    public BedrockContextEstimator(TokenCounter counter) {
        super(new GenericContentFormatter(), counter);
    }

    /** The density of the family the spec's wire id names. */
    public static TokenCounter counterFor(ModelSpec spec) {
        String wireModelId = spec.getWireModelId();
        if (AnthropicNaming.isClaude(wireModelId)) {
            return CLAUDE_COUNTER;
        }
        if (ModelLineage.bare(wireModelId).startsWith("cohere")) {
            return COHERE_COUNTER;
        }
        return TiktokenCounter.DEFAULT;
    }

    /** The estimator for the family the spec's wire id names. */
    public static BedrockContextEstimator forSpec(ModelSpec spec) {
        TokenCounter counter = counterFor(spec);
        if (counter == CLAUDE_COUNTER) {
            return CLAUDE;
        }
        if (counter == COHERE_COUNTER) {
            return COHERE;
        }
        return DEFAULT;
    }
}
