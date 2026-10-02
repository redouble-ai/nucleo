/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Cl100kTokenCounter} — verifies cl100k_base counting,
 * multiplier application, and null/empty handling.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-16)
 */
public class Cl100kTokenCounterTest {

    @Test
    void countTokens_applies_claude_multiplier() {
        Cl100kTokenCounter claude = new Cl100kTokenCounter(
            Cl100kTokenCounter.DEFAULT_MULTIPLIER_CLAUDE,
            Cl100kTokenCounter.DEFAULT_IMAGE_BASE_TOKENS_CLAUDE,
            0,
            Cl100kTokenCounter.DEFAULT_PDF_TOKENS_PER_KB);
        Cl100kTokenCounter tiktoken = new Cl100kTokenCounter(
            Cl100kTokenCounter.DEFAULT_MULTIPLIER_TIKTOKEN,
            Cl100kTokenCounter.DEFAULT_IMAGE_BASE_TOKENS_TIKTOKEN,
            Cl100kTokenCounter.DEFAULT_IMAGE_PER_TILE_TOKENS_TIKTOKEN,
            Cl100kTokenCounter.DEFAULT_PDF_TOKENS_PER_KB);
        String text = "The quick brown fox jumps over the lazy dog. ".repeat(20);
        int claudeCount = claude.countTokens(text);
        int tiktokenCount = tiktoken.countTokens(text);
        assertTrue(claudeCount > tiktokenCount,
            "Claude multiplier (1.10) should produce more tokens than tiktoken (1.00)");
        double ratio = (double) claudeCount / tiktokenCount;
        assertTrue(ratio > 1.05 && ratio < 1.15,
            "Claude/tiktoken ratio should be ~1.10, got " + ratio);
    }

    @Test
    void countTokens_returns_zero_for_null_and_empty() {
        Cl100kTokenCounter counter = new Cl100kTokenCounter(1.0, 100, 0, 100);
        assertEquals(0, counter.countTokens((String) null));
        assertEquals(0, counter.countTokens(""));
    }

    @Test
    void countTokens_non_zero_for_content() {
        Cl100kTokenCounter counter = new Cl100kTokenCounter(1.0, 100, 0, 100);
        int tokens = counter.countTokens("Hello, world!");
        assertTrue(tokens > 0, "Non-empty text must produce non-zero tokens");
    }

    @Test
    void custom_multiplier_scales_linearly() {
        Cl100kTokenCounter single = new Cl100kTokenCounter(1.0, 100, 0, 100);
        Cl100kTokenCounter doubled = new Cl100kTokenCounter(2.0, 100, 0, 100);
        String text = "The cl100k_base encoding is approximately 10% undersized for Claude.";
        int singleCount = single.countTokens(text);
        int doubledCount = doubled.countTokens(text);
        assertEquals(singleCount * 2, doubledCount,
            "2x multiplier should exactly double the base count");
    }
}
