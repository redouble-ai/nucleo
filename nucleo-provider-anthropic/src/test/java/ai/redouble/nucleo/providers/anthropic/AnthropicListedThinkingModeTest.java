/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.anthropic;

import ai.redouble.nucleo.harness.models.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The thinking mode a listed model's capabilities amount to: a model that does not think is
 * {@code NONE}, one that decides how much to think is {@code ADAPTIVE} whether or not it also
 * takes a budget, one that takes a budget only is {@code EXTENDED}, and one listed as thinking
 * in neither way states nothing the runtime can send.
 *
 * @author Andrey Santrosyan
 * @since 0.1.3 (2026-11-14)
 */
class AnthropicListedThinkingModeTest {
    @Test
    void theListedCapabilitiesSayHowTheModelThinks() {
        assertEquals(ThinkingMode.NONE, AnthropicDirectProvider.thinkingMode(false, false, false), "a model that does not think");
        assertEquals(ThinkingMode.ADAPTIVE, AnthropicDirectProvider.thinkingMode(true, true, false), "adaptive only, as the newest models are");
        assertEquals(ThinkingMode.ADAPTIVE, AnthropicDirectProvider.thinkingMode(true, true, true), "a model that accepts both is called adaptively");
        assertEquals(ThinkingMode.EXTENDED, AnthropicDirectProvider.thinkingMode(true, false, true), "a budget of the caller's only");
        assertNull(AnthropicDirectProvider.thinkingMode(true, false, false), "thinking in neither way the runtime sends is not a statement of a mode");
    }
}
