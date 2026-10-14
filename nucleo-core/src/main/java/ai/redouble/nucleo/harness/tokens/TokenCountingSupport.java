/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.tokens;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;

import java.util.*;

/**
 * The file arithmetic the counters share. Each counter owns its per-string and per-image
 * figures; a file's cost follows from them the same way for every counter.
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
}
