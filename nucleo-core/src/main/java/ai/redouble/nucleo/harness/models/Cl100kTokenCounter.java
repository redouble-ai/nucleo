/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.conversation.*;
import com.knuddels.jtokkit.*;
import com.knuddels.jtokkit.api.*;

import java.util.*;

/**
 * Counts tokens using OpenAI's cl100k_base encoding (via JTokkit) multiplied by a
 * per-family adjustment factor. The encoding itself is shared process-wide:
 * {@code GptBytePairEncoding} holds immutable BPE maps set at construction and is
 * documented as thread-safe for concurrent reads.
 * <p>
 * The multiplier compensates for the fact that cl100k_base is only an approximation
 * for Claude and Gemini (roughly 10% more tokens than OpenAI's own tokenization for
 * the same text, empirically). For native OpenAI models, pass 1.0.
 * <p>
 * Image and PDF token counts are heuristics (not the wire-format base64 length):
 * Anthropic and Gemini charge a fixed number of tokens per image and a per-KB rate
 * for PDF attachments. Defaults match Anthropic's published rates and Claude 3/4
 * Vision's observed behavior.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-16)
 */
public class Cl100kTokenCounter implements TokenCounter {
    public static final double DEFAULT_MULTIPLIER_CLAUDE = 1.10;
    public static final double DEFAULT_MULTIPLIER_GEMINI = 1.10;
    public static final double DEFAULT_MULTIPLIER_TIKTOKEN = 1.00;
    // Cohere's tokenizer bills 10-17% more tokens than cl100k on mixed-language corpora
    // (measured against Bedrock InputTokenCount metrics, 2026-08); 1.20 keeps declared
    // budgets above what the provider actually meters
    public static final double DEFAULT_MULTIPLIER_COHERE = 1.20;
    public static final int DEFAULT_IMAGE_BASE_TOKENS_CLAUDE = 1600;
    public static final int DEFAULT_IMAGE_BASE_TOKENS_GEMINI = 258;
    public static final int DEFAULT_IMAGE_BASE_TOKENS_TIKTOKEN = 85;
    public static final int DEFAULT_IMAGE_PER_TILE_TOKENS_TIKTOKEN = 170;
    public static final int DEFAULT_PDF_TOKENS_PER_KB = 450;
    private static final Encoding CL100K;

    static {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        CL100K = registry.getEncoding(EncodingType.CL100K_BASE);
    }

    private final double multiplier;
    private final int imageBaseTokens;
    private final int imagePerTileTokens;
    private final int pdfTokensPerKB;

    public Cl100kTokenCounter(double multiplier, int imageBaseTokens, int imagePerTileTokens, int pdfTokensPerKB) {
        this.multiplier = multiplier;
        this.imageBaseTokens = imageBaseTokens;
        this.imagePerTileTokens = imagePerTileTokens;
        this.pdfTokensPerKB = pdfTokensPerKB;
    }

    @Override
    public int countTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int baseCount = CL100K.countTokens(text);
        return (int)(baseCount * multiplier);
    }

    @Override
    public int countTokens(Message message) {
        return TokenCountingSupport.countMessage(message, this);
    }

    @Override
    public int countImageTokens(ImageBlock image) {
        if (image == null) {
            return 0;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(image.base64());
            int sizeKB = decoded.length / 1024;
            int sizeBasedEstimate = sizeKB * 4;
            return Math.max(imageBaseTokens, sizeBasedEstimate);
        }
        catch (Exception e) {
            if (imagePerTileTokens > 0) {
                int estimatedTiles = 4;
                return imageBaseTokens + (imagePerTileTokens * estimatedTiles);
            }
            return imageBaseTokens;
        }
    }

    @Override
    public int countFileTokens(FileBlock file) {
        return TokenCountingSupport.countFile(file, imageBaseTokens, pdfTokensPerKB);
    }
}
