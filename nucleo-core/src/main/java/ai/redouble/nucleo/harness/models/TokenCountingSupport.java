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
 * Shared traversal for counting tokens in {@link Message} instances. All
 * {@link TokenCounter} implementations walk blocks the same way; only the
 * per-string and per-image/file arithmetic differs. Delegating here keeps that
 * arithmetic the counter's private concern while letting the walker stay uniform.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-16)
 */
final class TokenCountingSupport {
    private TokenCountingSupport() {}

    /**
     * File tokens by payload size: a PDF at the counter's per-KB rate, an image at the
     * counter's image floor or four tokens per KB, anything else at a hundred per KB. A
     * payload that does not decode counts as ten KB of PDF.
     */
    static int countFile(FileBlock file, int imageBaseTokens, int pdfTokensPerKB) {
        if (file == null) {
            return 0;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(file.base64());
            int sizeKB = decoded.length / 1024;
            String mimeType = file.mimeType();
            if (mimeType != null && mimeType.equals("application/pdf")) {
                return sizeKB * pdfTokensPerKB;
            }
            if (mimeType != null && mimeType.startsWith("image/")) {
                return Math.max(imageBaseTokens, sizeKB * 4);
            }
            return sizeKB * 100;
        }
        catch (Exception e) {
            return pdfTokensPerKB * 10;
        }
    }

    static int countMessage(Message message, TokenCounter counter) {
        if (message == null) {
            return 0;
        }
        int total = 0;
        String content = message.getRawContent();
        if (content != null && !content.isEmpty()) {
            total += counter.countTokens(content);
        }
        if (message instanceof OutgoingMessage<?> outgoing) {
            List<ContentBlock> blocks = outgoing.getContentBlocks();
            if (blocks != null && !blocks.isEmpty()) {
                for (ContentBlock block : blocks) {
                    if (block instanceof ImageBlock ib) {
                        total += counter.countImageTokens(ib);
                    }
                    else if (block instanceof FileBlock fb) {
                        total += counter.countFileTokens(fb);
                    }
                }
            }
            else {
                List<ImageBlock> imageBlocks = outgoing.getImageBlocks();
                if (imageBlocks != null) {
                    for (ImageBlock image : imageBlocks) {
                        total += counter.countImageTokens(image);
                    }
                }
                List<FileBlock> fileBlocks = outgoing.getFileBlocks();
                if (fileBlocks != null) {
                    for (FileBlock file : fileBlocks) {
                        total += counter.countFileTokens(file);
                    }
                }
            }
        }
        return total;
    }

}
