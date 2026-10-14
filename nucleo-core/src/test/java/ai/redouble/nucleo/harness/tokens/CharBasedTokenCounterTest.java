/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.tokens;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link CharBasedTokenCounter}, the fallback with no encoding: a quarter of a token per
 * character, truncating; an image at a floor of 1000 or four tokens per KB; a file under the
 * shared file rule.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-16)
 */
public class CharBasedTokenCounterTest {

    @Test
    void countTokens_returns_length_divided_by_four() {
        CharBasedTokenCounter counter = new CharBasedTokenCounter(
            CharBasedTokenCounter.DEFAULT_IMAGE_BASE_TOKENS,
            CharBasedTokenCounter.DEFAULT_PDF_TOKENS_PER_KB);
        String text = "a".repeat(400);
        assertEquals(100, counter.countTokens(text));
    }

    @Test
    void countTokens_returns_zero_for_null_and_empty() {
        CharBasedTokenCounter counter = new CharBasedTokenCounter(1000, 450);
        assertEquals(0, counter.countTokens(null));
        assertEquals(0, counter.countTokens(""));
    }

    @Test
    void an_image_costs_the_floor_or_four_per_kb_and_the_default_floor_is_1000() {
        CharBasedTokenCounter counter = new CharBasedTokenCounter(CharBasedTokenCounter.DEFAULT_IMAGE_BASE_TOKENS, CharBasedTokenCounter.DEFAULT_PDF_TOKENS_PER_KB);
        assertEquals(1000, CharBasedTokenCounter.DEFAULT_IMAGE_BASE_TOKENS);
        assertEquals(1000, counter.countImageTokens(new ImageBlock(Base64.getEncoder().encodeToString(new byte[3 * 1024]), "image/png", null)), "a small image costs the floor");
        assertEquals(1200, counter.countImageTokens(new ImageBlock(Base64.getEncoder().encodeToString(new byte[300 * 1024]), "image/png", null)), "a large one four tokens per KB");
        assertEquals(1000, counter.countImageTokens(new ImageBlock("%%not base64%%", "image/png", null)), "bytes that do not decode cost the floor");
    }

    @Test
    void a_file_follows_the_shared_file_rule() {
        CharBasedTokenCounter counter = new CharBasedTokenCounter(1000, 450);
        String tenKb = Base64.getEncoder().encodeToString(new byte[10 * 1024]);
        assertEquals(4500, counter.countFileTokens(new FileBlock(tenKb, "application/pdf", "doc.pdf")), "a PDF costs 450 per KB");
        assertEquals(1000, counter.countFileTokens(new FileBlock(tenKb, "image/png", "shot.png")), "an image file follows the image rule, floor 1000");
        assertEquals(1000, counter.countFileTokens(new FileBlock(tenKb, "text/plain", "notes.txt")), "any other file costs 100 per KB");
    }

    @Test
    void countTokens_truncates_partial_quartet() {
        CharBasedTokenCounter counter = new CharBasedTokenCounter(1000, 450);
        assertEquals(0, counter.countTokens("abc"));
        assertEquals(1, counter.countTokens("abcd"));
        assertEquals(1, counter.countTokens("abcde"));
    }
}
