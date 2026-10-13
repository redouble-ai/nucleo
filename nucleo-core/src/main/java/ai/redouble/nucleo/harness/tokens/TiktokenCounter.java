/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.tokens;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import com.knuddels.jtokkit.*;
import com.knuddels.jtokkit.api.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Counts text with one of OpenAI's tiktoken encodings (via JTokkit) times a density
 * multiplier. The encodings are shared process-wide: a {@code GptBytePairEncoding} holds
 * immutable BPE maps and is documented as thread-safe for concurrent reads.
 *
 * <p>A model whose tokenizer is public and shipped by JTokkit counts exactly with multiplier
 * 1.0 (the current OpenAI chat models on {@code o200k_base}). A model whose tokenizer is not
 * public is counted with the nearest encoding and a multiplier its provider module calibrated
 * against the provider's own usage reports; the multiplier is the provider's knowledge, so the
 * provider module constructs the counter and this class carries no provider's number.
 *
 * <p>Image and file counts are rules, not text. Here an image costs its floor or four tokens
 * per KB of its bytes, whichever is more, and the floor alone when its bytes do not decode; a
 * provider whose image pricing is published overrides {@link #countImageTokens} with that rule
 * and falls back to this one for an image whose size it cannot read. A file follows
 * {@link TokenCountingSupport#countFile}. The defaults here are the generic figures for a
 * provider that states none of its own.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-16)
 */
public class TiktokenCounter implements TokenCounter {
    public static final int DEFAULT_IMAGE_BASE_TOKENS = 85;
    public static final int DEFAULT_PDF_TOKENS_PER_KB = 450;
    private static final EncodingRegistry REGISTRY = Encodings.newDefaultEncodingRegistry();
    private static final Map<EncodingType, Encoding> ENCODINGS = new ConcurrentHashMap<>();
    /** The provider-neutral counter: {@code cl100k_base} at density 1.0 with the generic image and PDF figures. */
    public static final TiktokenCounter DEFAULT = new TiktokenCounter(EncodingType.CL100K_BASE, 1.0,
            DEFAULT_IMAGE_BASE_TOKENS, DEFAULT_PDF_TOKENS_PER_KB);
    private final Encoding encoding;
    private final double multiplier;
    private final int imageBaseTokens;
    private final int pdfTokensPerKB;

    /**
     * @param encodingType    the tiktoken encoding to count text with
     * @param multiplier      the density of the model's tokenizer against that encoding
     * @param imageBaseTokens the least an image costs
     * @param pdfTokensPerKB  what a KB of PDF costs
     */
    public TiktokenCounter(EncodingType encodingType, double multiplier, int imageBaseTokens, int pdfTokensPerKB) {
        this.encoding = ENCODINGS.computeIfAbsent(encodingType, REGISTRY::getEncoding);
        this.multiplier = multiplier;
        this.imageBaseTokens = imageBaseTokens;
        this.pdfTokensPerKB = pdfTokensPerKB;
    }

    /** The least an image costs on this counter. */
    public int imageBaseTokens() {
        return imageBaseTokens;
    }

    @Override
    public int countTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int baseCount = encoding.countTokens(text);
        return (int)(baseCount * multiplier);
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
        catch (IllegalArgumentException notBase64) {
            return imageBaseTokens;
        }
    }

    @Override
    public int countFileTokens(FileBlock file) {
        return TokenCountingSupport.countFile(file, imageBaseTokens, pdfTokensPerKB);
    }
}
