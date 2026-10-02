/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.anthropic;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An Anthropic provider names what it lists through {@code AnthropicNaming}: Claude ids the Claude
 * way, suffixed with the provider's channel, and every other vendor's model the generic way, so the
 * same model has one identity whichever key of the platform listed it.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
class AnthropicProviderNamingTest {

    @Test
    void anAnthropicProviderNamesOtherVendorsModelsTheGenericWay() {
        AnthropicDirectProvider provider = new AnthropicDirectProvider();
        assertEquals("gpt-6-astra", provider.identityOf("us.openai.gpt-6-astra"));
        assertEquals("nemotron-nano-3-30b", provider.identityOf("nvidia.nemotron-nano-3-30b"));
        assertEquals("opus-5", provider.identityOf("us.anthropic.claude-opus-5"));
        assertEquals("claude-opus-5-direct", provider.catalogIdOf("opus-5", "us.anthropic.claude-opus-5"));
        assertEquals("gpt-6-astra", provider.catalogIdOf("gpt-6-astra", "us.openai.gpt-6-astra"));
    }
}
