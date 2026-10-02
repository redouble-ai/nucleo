/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CharBasedTokenCounter} — character-count/4 fallback.
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
        assertEquals(0, counter.countTokens((String) null));
        assertEquals(0, counter.countTokens(""));
    }

    @Test
    void countTokens_truncates_partial_quartet() {
        CharBasedTokenCounter counter = new CharBasedTokenCounter(1000, 450);
        assertEquals(0, counter.countTokens("abc"));
        assertEquals(1, counter.countTokens("abcd"));
        assertEquals(1, counter.countTokens("abcde"));
    }
}
