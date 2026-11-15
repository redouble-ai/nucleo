/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.anthropic;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What this artifact contributes once it is on the classpath: its provider is discovered by
 * key, the fragment's entries load with the provider's defaults, and its image encoder builds
 * a native provider object without routing through text.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-11)
 */
class AnthropicProviderModuleTest {

    @Test
    void providerIsDiscoveredByKey() {
        assertInstanceOf(AnthropicDirectProvider.class, ClientProviders.get("anthropic-direct"));
    }

    @Test
    void theProviderClaimsClaudeAndNothingElse() {
        ClientProvider<?> provider = ClientProviders.get("anthropic-direct");
        assertTrue(provider.claims("claude-opus-5"), "Claude is the family the Anthropic SDK is written for");
        assertFalse(provider.claims("gpt-6"));
    }

    @Test
    void theFragmentsEntriesLoadWithTheProvidersDefaults() {
        ModelSpec spec = Models.spec("claude-opus-5-direct");
        assertEquals(4, spec.getCacheBreakpoints(), "the provider default reaches every entry");
    }

    @Test
    void nativeImageEncoderBuildsAProviderObjectWithoutTheWrapper() {
        var encoder = new AnthropicImageBlockEncoder();
        encoder.setTextWrapper(t -> { throw new AssertionError("native encoder used the text wrapper"); });
        assertNotNull(encoder.encode(new ImageBlock("aGk=", "image/png", null)));
    }
}
