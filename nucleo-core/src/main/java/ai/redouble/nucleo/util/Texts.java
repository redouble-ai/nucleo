/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.util;

import java.util.*;

/**
 * Cutting text that has no natural boundary left to cut at.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-17)
 */
public final class Texts {

    private Texts() {}

    /**
     * The text in pieces of at most {@code maxChars} characters, each cut at the last space of
     * its window when that space falls in the window's second half, else exactly at the limit.
     * Pieces are trimmed and the whitespace between them dropped. The last resort of a splitter
     * whose paragraph and sentence boundaries have run out.
     */
    public static List<String> splitAtWords(String text, int maxChars) {
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxChars, text.length());
            if (end < text.length()) {
                int lastSpace = text.lastIndexOf(' ', end);
                if (lastSpace > start + maxChars / 2) {
                    end = lastSpace;
                }
            }
            pieces.add(text.substring(start, end).trim());
            start = end;
            while (start < text.length() && Character.isWhitespace(text.charAt(start))) {
                start++;
            }
        }
        return pieces;
    }
}
