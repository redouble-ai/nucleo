/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Claude names from every surface's spelling land on the catalog's identity and id.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
class AnthropicNamingTest {

    @Test
    void everySpellingOfAClaudeIdGivesTheCatalogIdentity() {
        assertEquals("opus-4.7", AnthropicNaming.identityOf("us.anthropic.claude-opus-4-7"));
        assertEquals("opus-4.5", AnthropicNaming.identityOf("global.anthropic.claude-opus-4-5-20251101-v1:0"));
        assertEquals("opus-5", AnthropicNaming.identityOf("anthropic.claude-opus-5"));
        assertEquals("opus-4.1", AnthropicNaming.identityOf("claude-opus-4-1-20250805"));
        assertEquals("fable-5.1", AnthropicNaming.identityOf("anthropic.claude-fable-5-1"));
        assertEquals("haiku-3", AnthropicNaming.identityOf("eu.anthropic.claude-3-haiku-20240307-v1:0"), "the old version-first spelling");
        assertEquals("sonnet-4", AnthropicNaming.identityOf("anthropic.claude-sonnet-4-20250514-v1:0"));
    }

    @Test
    void catalogIdsCarryTheChannel() {
        assertEquals("claude-opus-4-7-bedrock", AnthropicNaming.catalogId("opus-4.7", "bedrock"));
        assertEquals("claude-opus-5-mantle", AnthropicNaming.catalogId("opus-5", "mantle"));
        assertEquals("claude-fable-5-1-direct", AnthropicNaming.catalogId("fable-5.1", "direct"));
    }

    @Test
    void onlyClaudeIdsAreClaude() {
        assertTrue(AnthropicNaming.isClaude("global.anthropic.claude-haiku-4-5-20251001-v1:0"));
        assertTrue(AnthropicNaming.isClaude("claude-opus-5"));
        assertFalse(AnthropicNaming.isClaude("amazon.nova-lite-v1:0"));
        assertFalse(AnthropicNaming.isClaude("openai.gpt-oss-120b-1:0"));
    }
}
