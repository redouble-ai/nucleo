/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.tokens;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;

import java.util.*;

/**
 * The fallback counter for a provider with no known tokenizer: a quarter of a token per
 * character, truncating, and nothing for null or empty text. An image costs the floor given at
 * construction or four tokens per KB of its bytes, whichever is more, and the floor alone when
 * its bytes do not decode; a file follows {@link TokenCountingSupport#countFile} with the
 * floor and the PDF rate given at construction; {@link #DEFAULT_IMAGE_BASE_TOKENS} is 1000 and
 * {@link #DEFAULT_PDF_TOKENS_PER_KB} 450.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-16)
 */
public class CharBasedTokenCounter implements TokenCounter {
    public static final int DEFAULT_IMAGE_BASE_TOKENS = 1000;
    public static final int DEFAULT_PDF_TOKENS_PER_KB = 450;
    private final int imageBaseTokens;
    private final int pdfTokensPerKB;

    public CharBasedTokenCounter(int imageBaseTokens, int pdfTokensPerKB) {
        this.imageBaseTokens = imageBaseTokens;
        this.pdfTokensPerKB = pdfTokensPerKB;
    }

    @Override
    public int countTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.length() / 4;
    }

    @Override
    public int countImageTokens(ImageBlock image) {
        try {
            byte[] decoded = Base64.getDecoder().decode(image.base64());
            int sizeKB = decoded.length / 1024;
            return Math.max(imageBaseTokens, sizeKB * 4);
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
