/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.conversation.*;

import java.util.*;

/**
 * Character-count / 4 fallback counter for providers with no known tokenizer.
 * Image and file heuristics are configurable via constructor arguments.
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
            return Math.max(imageBaseTokens, sizeKB * 4);
        }
        catch (Exception e) {
            return imageBaseTokens;
        }
    }

    @Override
    public int countFileTokens(FileBlock file) {
        return TokenCountingSupport.countFile(file, imageBaseTokens, pdfTokensPerKB);
    }
}
