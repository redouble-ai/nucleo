/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.harness.models.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Who made the model behind an entry, as each provider of this module answers it
 * ({@code ClientProvider.vendor}).
 *
 * @author Andrey Santrosyan
 * @since 0.1.5 (2026-11-23)
 */
class OpenAIVendorTest {
    private static StandardModelSpec entry(String identity, String wireModelId) {
        StandardModelSpec spec = new StandardModelSpec();
        spec.setId(identity + "-test");
        spec.setIdentity(identity);
        spec.setWireModelId(wireModelId);
        return spec;
    }

    @Test
    void openAIsOwnEndpointsServeOpenAIsModels() {
        assertEquals("openai", new OpenAIProvider().vendor(entry("gpt-5.4", "gpt-5.4")));
        assertEquals("openai", new OpenAIChatCompletionsProvider().vendor(entry("o3", "o3")));
    }

    @Test
    void azureFoundryKnowsOpenAIsModelsByTheirIdentityWhateverTheDeploymentIsCalled() {
        AzureFoundryOpenAIProvider foundry = new AzureFoundryOpenAIProvider();
        assertEquals("openai", foundry.vendor(entry("gpt-6-astra", "our-strongest")));
        assertEquals("openai", foundry.vendor(entry("gpt-5.4-mini", "gpt-5.4-mini")));
        assertEquals("openai", foundry.vendor(entry("o4-mini", "reasoning-small")));
        assertNull(foundry.vendor(entry("deepseek-v4-pro", "DeepSeek-V4-Pro")), "Foundry does not say who made another vendor's model");
        assertNull(foundry.vendor(entry("omni-large", "omni-large")), "a name that only starts with the letter is no o-series model");
    }

    @Test
    void anEndpointThatOnlySpeaksTheDialectNamesNoVendor() {
        assertNull(new OpenAICompatibleProvider().vendor(entry("kev-9b", "kev-9b")));
    }
}
