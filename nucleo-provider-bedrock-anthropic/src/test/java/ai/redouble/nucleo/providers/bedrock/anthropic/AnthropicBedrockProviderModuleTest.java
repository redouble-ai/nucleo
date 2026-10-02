/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.bedrock.anthropic;

import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.providers.anthropic.*;
import ai.redouble.nucleo.providers.bedrock.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What this artifact adds on top of {@code nucleo-provider-bedrock}: the Anthropic-on-Bedrock and
 * Mantle providers are discovered by key beside the base's Converse and Cohere embeddings, its
 * fragment's Claude entries come up as Anthropic specs through the anthropic artifact it carries,
 * a Claude entry that needs a provider links to an Anthropic surface (they claim Claude) before
 * Converse, and resolving a provider never constructs its client.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
class AnthropicBedrockProviderModuleTest {

    @Test
    void providersAreDiscoveredByKeyBesideTheBase() {
        assertInstanceOf(AnthropicBedrockProvider.class, ClientProviders.get("anthropic-bedrock"));
        assertInstanceOf(AnthropicBedrockMantleProvider.class, ClientProviders.get("anthropic-bedrock-mantle"));
        assertInstanceOf(BedrockConverseProvider.class, ClientProviders.get("bedrock-converse"));
    }

    @Test
    void anthropicOnBedrockEntriesAreAnthropicSpecs() throws IOException {
        // The suite's Models facade runs on the shared test fixture; this test's subject is
        // the shipped fragments themselves, loaded the way the runtime's fallback does.
        List<JsonModelsBackend.Layer> layers = new ArrayList<>();
        for (Enumeration<URL> fragments = getClass().getClassLoader().getResources(JsonModelsBackend.FRAGMENT_RESOURCE); fragments.hasMoreElements(); ) {
            URL fragment = fragments.nextElement();
            try (InputStream in = fragment.openStream()) {
                layers.add(new JsonModelsBackend.Layer(fragment.toString(), new String(in.readAllBytes(), StandardCharsets.UTF_8), true));
            }
        }
        JsonModelsBackend fragmentCatalog = new JsonModelsBackend(layers);
        assertInstanceOf(AnthropicModelSpec.class, fragmentCatalog.spec("claude-opus-5-bedrock"));
        assertInstanceOf(AnthropicModelSpec.class, fragmentCatalog.spec("claude-opus-5-mantle"));
    }

    @Test
    void aClaudeEntryLinksToTheAnthropicSurfaceThatClaimsItAndAnyOtherToConverse() {
        Map<String, ClientProvider<?>> providers = ClientProviders.all();
        assertEquals("anthropic-bedrock", ProviderLinks.resolve(providers, "bedrock", ModelKind.LLM, "us.anthropic.claude-opus-5").key(),
                "of the providers addressing bedrock-runtime, the Anthropic runtime surface claims Claude and Converse only serves it");
        assertEquals("bedrock-converse", ProviderLinks.resolve(providers, "bedrock", ModelKind.LLM, "us.amazon.nova-micro-v1:0").key(),
                "no Anthropic surface claims another vendor's model");
        assertEquals("bedrock-cohere-embeddings", ProviderLinks.resolve(providers, "bedrock", ModelKind.EMBEDDINGS, "cohere.embed-v4:0").key());
        assertEquals("bedrock-mantle", ClientProviders.get("anthropic-bedrock-mantle").addressing(), "Mantle spells its ids for its own endpoint");
        assertEquals("anthropic-bedrock-mantle", ProviderLinks.resolve(providers, "bedrock-mantle", ModelKind.LLM, "anthropic.claude-opus-5").key(),
                "an id spelled for Mantle links to the Mantle surface alone");
    }

    @Test
    void providerResolutionDoesNotConstructClient() {
        // Resolving the provider must not build its client, which reads credentials.
        assertDoesNotThrow(() -> ClientProviders.get("anthropic-bedrock"));
    }
}
