/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.tokens;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import com.knuddels.jtokkit.api.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TiktokenCounter}: an encoding times a density. The encoding is the one named, the
 * density scales linearly, and nothing is counted for nothing. The neutral counter is
 * {@code cl100k_base} at 1.0 with an image floor of 85, 170 per tile and 450 per PDF KB. An
 * image costs its floor or four tokens per KB, whichever is more, and the floor plus four
 * tiles when its bytes do not decode; a file costs 450 per KB as a PDF, the image rule as an
 * image, 100 per KB otherwise, and 4500 when its bytes do not decode.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-16)
 */
public class TiktokenCounterTest {

    private static String bytes(int kb) {
        return Base64.getEncoder().encodeToString(new byte[kb * 1024]);
    }

    @Test
    void the_neutral_counter_is_cl100k_at_one_with_the_generic_figures() {
        String text = "The quick brown fox jumps over the lazy dog. ".repeat(20);
        assertEquals(new TiktokenCounter(EncodingType.CL100K_BASE, 1.0, 85, 170, 450).countTokens(text), TiktokenCounter.DEFAULT.countTokens(text),
                "DEFAULT counts as cl100k_base at density 1.0");
        assertEquals(85, TiktokenCounter.DEFAULT_IMAGE_BASE_TOKENS);
        assertEquals(170, TiktokenCounter.DEFAULT_IMAGE_PER_TILE_TOKENS);
        assertEquals(450, TiktokenCounter.DEFAULT_PDF_TOKENS_PER_KB);
    }

    @Test
    void an_image_costs_its_floor_or_four_tokens_per_kb_whichever_is_more() {
        TiktokenCounter counter = TiktokenCounter.DEFAULT;
        assertEquals(0, counter.countImageTokens(null), "no image, nothing");
        assertEquals(85, counter.countImageTokens(new ImageBlock(bytes(3), "image/png", null)), "a small image costs the floor");
        assertEquals(400, counter.countImageTokens(new ImageBlock(bytes(100), "image/png", null)), "a large image costs four tokens per KB");
        TiktokenCounter claudeLike = new TiktokenCounter(EncodingType.CL100K_BASE, 1.10, 1600, 0, 450);
        assertEquals(1600, claudeLike.countImageTokens(new ImageBlock(bytes(100), "image/png", null)), "a higher floor wins over the size rule");
        assertEquals(85 + 170 * 4, counter.countImageTokens(new ImageBlock("%%not base64%%", "image/png", null)),
                "bytes that do not decode cost the floor plus four tiles' allowance");
        assertEquals(1600, claudeLike.countImageTokens(new ImageBlock("%%not base64%%", "image/png", null)),
                "and the floor alone on a counter with no per-tile allowance");
    }

    @Test
    void a_file_costs_by_its_kind_and_size() {
        TiktokenCounter counter = TiktokenCounter.DEFAULT;
        assertEquals(0, counter.countFileTokens(null), "no file, nothing");
        assertEquals(10 * 450, counter.countFileTokens(new FileBlock(bytes(10), "application/pdf", "doc.pdf")), "a PDF costs 450 per KB");
        assertEquals(85, counter.countFileTokens(new FileBlock(bytes(10), "image/png", "shot.png")), "an image file follows the image rule");
        assertEquals(10 * 100, counter.countFileTokens(new FileBlock(bytes(10), "text/plain", "notes.txt")), "any other file costs 100 per KB");
        assertEquals(4500, counter.countFileTokens(new FileBlock("%%not base64%%", "application/pdf", "doc.pdf")), "bytes that do not decode cost ten KB of PDF");
    }

    @Test
    void density_scales_the_encodings_count() {
        TiktokenCounter dense = new TiktokenCounter(EncodingType.CL100K_BASE, 1.10, 100, 0, 100);
        TiktokenCounter plain = new TiktokenCounter(EncodingType.CL100K_BASE, 1.00, 100, 0, 100);
        String text = "The quick brown fox jumps over the lazy dog. ".repeat(20);
        int denseCount = dense.countTokens(text);
        int plainCount = plain.countTokens(text);
        assertTrue(denseCount > plainCount, "a density above one yields more tokens than the encoding alone");
        double ratio = (double)denseCount / plainCount;
        assertTrue(ratio > 1.05 && ratio < 1.15, "the ratio is the density, got " + ratio);
    }

    @Test
    void countTokens_returns_zero_for_null_and_empty() {
        TiktokenCounter counter = new TiktokenCounter(EncodingType.CL100K_BASE, 1.0, 100, 0, 100);
        assertEquals(0, counter.countTokens(null));
        assertEquals(0, counter.countTokens(""));
    }

    @Test
    void countTokens_non_zero_for_content() {
        assertTrue(TiktokenCounter.DEFAULT.countTokens("Hello, world!") > 0, "non-empty text must produce non-zero tokens");
    }

    @Test
    void custom_density_scales_linearly() {
        TiktokenCounter single = new TiktokenCounter(EncodingType.CL100K_BASE, 1.0, 100, 0, 100);
        TiktokenCounter doubled = new TiktokenCounter(EncodingType.CL100K_BASE, 2.0, 100, 0, 100);
        String text = "The cl100k_base encoding is approximately 10% undersized for Claude.";
        assertEquals(single.countTokens(text) * 2, doubled.countTokens(text), "2x density exactly doubles the base count");
    }

    @Test
    void the_o200k_encoding_is_its_own_count() {
        TiktokenCounter o200k = new TiktokenCounter(EncodingType.O200K_BASE, 1.0, 100, 0, 100);
        TiktokenCounter cl100k = new TiktokenCounter(EncodingType.CL100K_BASE, 1.0, 100, 0, 100);
        String text = "Les reseaux industriels redondants basculent en moins d'une demi-seconde. ".repeat(30);
        assertNotEquals(cl100k.countTokens(text), o200k.countTokens(text),
                "two encodings tokenize the same text differently; a provider names the one its models use");
        assertTrue(o200k.countTokens(text) > 0);
    }
}
